package com.morpheusdata.hyperv.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.hyperv.HyperVApiService
import com.morpheusdata.hyperv.utils.HypervOptsUtility
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.Network
import com.morpheusdata.model.NetworkSubnet
import com.morpheusdata.model.NetworkSubnetType
import com.morpheusdata.model.NetworkType
import com.morpheusdata.model.projection.NetworkIdentityProjection
import groovy.util.logging.Slf4j

/**
 * @author razi.ahmad
 */

@Slf4j
class NetworkSync {

    private MorpheusContext morpheusContext
    private Cloud cloud
    private HyperVApiService apiService

    NetworkSync(MorpheusContext morpheusContext, Cloud cloud, HyperVApiService apiService) {
        this.cloud = cloud
        this.morpheusContext = morpheusContext
        this.apiService = apiService
    }

    def execute() {
        log.debug "NetworkSync"
        try {
            def codes = ['hypervExternalNetwork', 'hypervInternalNetwork']
            def networkTypes = morpheusContext.services.network.type.list(new DataQuery().withFilter('code', 'in', codes))
            NetworkSubnetType subnetType = new NetworkSubnetType(code: 'hyperv')
            def server = morpheusContext.services.computeServer.find(new DataQuery().withFilter('zone.id', cloud.id))

            def hypervOpts = HypervOptsUtility.getHypervZoneOpts(morpheusContext, cloud)
            hypervOpts += HypervOptsUtility.getHypervHypervisorOpts(server)

            def listResults = apiService.listVmSwitches(hypervOpts)

            if (listResults.success == true && listResults?.vmSwitchList) {
                def existingItems = morpheusContext.async.cloud.network.listIdentityProjections(new DataQuery()
                        .withFilter('zone.id', cloud.id).withFilter('type', 'in', networkTypes))
                SyncTask<NetworkIdentityProjection, Map, Network> syncTask = new SyncTask<>(existingItems, listResults.vmSwitchList as Collection<Map>)

                syncTask.addMatchFunction { NetworkIdentityProjection morpheusItem, Map cloudItem ->
                    morpheusItem?.externalId == cloudItem?.name
                }.onDelete { removeItems ->
                    morpheusContext.async.cloud.network.remove(removeItems).blockingGet()
                }.onUpdate { List<SyncTask.UpdateItem<Network, Map>> updateItems ->
                    updateMatchedNetworks(updateItems, networkTypes, subnetType, server)
                }.onAdd { itemsToAdd ->
                    addMissingNetworks(itemsToAdd, networkTypes, subnetType, server)
                }.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<NetworkIdentityProjection, Map>> updateItems ->
                    return morpheusContext.async.cloud.network.listById(updateItems.collect { it.existingItem.id } as List<Long>)
                }.start()
            } else {
                log.error("Error not getting the listNetworks")
            }
        } catch (e) {
            log.error("cacheNetworks error: ${e}", e)
        }
    }

    private addMissingNetworks(Collection<Map> addList, List<NetworkType> networkTypes, NetworkSubnetType subnetType, ComputeServer server) {
        log.debug("NetworkSync >> addMissingNetworks >> called")
        def networkAdds = []
        try {
            addList?.each { cloudItem ->
                def networkType = networkTypes.find { type -> type.externalType == cloudItem.type }

                def networkConfig = [
                        code      : "hyperv.network.${cloud.id}.${cloudItem.name}",
                        category  : "hyperv.network.${cloud.id}",
                        cloud     : cloud,
                        dhcpServer: true,
                        name      : cloudItem.name,
                        externalId: cloudItem.name,
                        type      : networkType,
                        refType   : 'ComputeZone',
                        refId     : cloud.id,
                        owner     : cloud.owner,
                        active    : cloud.defaultNetworkSyncActive
                ]
                Network networkAdd = new Network(networkConfig)
                networkAdds << networkAdd
            }

            // Perform bulk create of networks
            if (networkAdds.size() > 0) {
                morpheusContext.async.cloud.network.bulkCreate(networkAdds).blockingGet()

                // Now add subnets to the created networks
                networkAdds.each { networkAdd ->
                    def cloudItem = addList.find { it.name == networkAdd.name } // Find corresponding cloud item

                    if (cloudItem) {
                        def subnetConfig = [
                                account             : server.account,
                                category            : "hyperv.network.${cloud.id}.${server.id}",
                                networkSubnetType   : subnetType,
                                code                : "hyperv.network.${cloud.id}.${server.id}.${cloudItem.name}",
                                name                : cloudItem.name,
                                externalId          : cloudItem.name,
                                refType             : 'ComputeServer',
                                refId               : server.id,
                                description         : cloudItem.name
                        ]
                        def addSubnet = new NetworkSubnet(subnetConfig)
                        // Create subnet for the network
                        morpheusContext.async.networkSubnet.create([addSubnet], networkAdd).blockingGet()
                    }
                }

                // Perform bulk save of networks
                morpheusContext.async.cloud.network.bulkSave(networkAdds).blockingGet()
            }
        } catch (e) {
            log.error "Error in adding Network sync ${e}", e
        }
    }

    private updateMatchedNetworks(List<SyncTask.UpdateItem<Network, Map>> updateList, List<NetworkType> networkTypes, NetworkSubnetType subnetType, ComputeServer server) {
        log.debug("NetworkSync >> updateMatchedNetworks >> Entered")
        List<Network> itemsToUpdate = []
        try {
            for (update in updateList) {
                Network existingItem = update.existingItem
                Map masterItem = update.masterItem
                def save = false
                def type = networkTypes.find{ type -> type.externalType == masterItem.type }
                if(type?.id != existingItem.type?.id) {
                    existingItem.type = type
                    save = true
                }
                def existingSubnet = existingItem.subnets?.find{ subnet -> subnet.refType == 'ComputeServer' && subnet.refId == server.id }
                if(!existingSubnet) {
                    def subnetConfig = [
                            account             : server.account,
                            category            : "hyperv.network.${cloud.id}.${server.id}",
                            networkSubnetType   : subnetType,
                            code                : "hyperv.network.${cloud.id}.${server.id}.${masterItem.name}",
                            name                : masterItem.name,
                            externalId          : masterItem.name,
                            refType             : 'ComputeServer',
                            refId               : server.id,
                            description         : masterItem.name
                    ]
                    def addSubnet = new NetworkSubnet(subnetConfig)
                    morpheusContext.async.networkSubnet.create([addSubnet], existingItem)
                }

                if (save) {
                    itemsToUpdate << existingItem
                }
            }
            if (itemsToUpdate.size() > 0) {
                morpheusContext.async.cloud.network.save(itemsToUpdate).blockingGet()
            }
        } catch(e) {
            log.error "Error in update Network sync ${e}", e
        }
    }
}