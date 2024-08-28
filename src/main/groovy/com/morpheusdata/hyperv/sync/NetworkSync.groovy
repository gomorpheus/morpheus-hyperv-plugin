package com.morpheusdata.hyperv.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.hyperv.HyperVApiService
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
    private ComputeServer server
    private HyperVApiService apiService

    NetworkSync(MorpheusContext morpheusContext, ComputeServer server, HyperVApiService apiService) {
        this.server = server
        this.morpheusContext = morpheusContext
        this.apiService = apiService
    }

    def execute() {
        log.debug "NetworkSync"
        try {
            def codes = ['hypervExternalNetwork', 'hypervInternalNetwork']
            def networkTypes = codes.collect { code -> new NetworkType(code: code) }
            log.info("RAZI :: networkTypes: ${networkTypes}")
            NetworkSubnetType subnetType = new NetworkSubnetType(code: 'hyperv')
            log.info("RAZI :: subnetType: ${subnetType}")

            def cloudConfig = server.cloud.getConfigMap()
            log.info("RAZI :: cloudConfig: ${cloudConfig}")
            def opts = [
                    sshHost         : cloudConfig.hypervHost,
                    sshPort         : cloudConfig.winrmPort,
                    sshUsername     : cloudConfig.username,
                    sshPassword     : cloudConfig.password
            ]
            log.info("RAZI :: opts: ${opts}")
            def listResults = apiService.listVmSwitches(opts)
            log.info("RAZI :: listResults: ${listResults}")

            if (listResults.success == true && listResults?.vmSwitchList) {
                def existingItems = morpheusContext.async.network.listIdentityProjections(new DataQuery()
                        .withFilter('zone.id', server.cloud.id).withFilter('type', 'in', networkTypes))

                SyncTask<NetworkIdentityProjection, Map, Network> syncTask = new SyncTask<>(existingItems, listResults.vmSwitchList as Collection<Map>)
                syncTask.addMatchFunction { NetworkIdentityProjection morpheusItem, Map cloudItem ->
                    log.info("RAZI :: morpheusItem?.name: ${morpheusItem?.name}")
                    log.info("RAZI :: cloudItem?.name: ${cloudItem?.name}")
                    morpheusItem?.name == cloudItem?.name
                }.onDelete { removeItems ->
                    log.info("RAZI :: removeItems START")
                    morpheusContext.async.cloud.network.remove(removeItems).blockingGet()
                    log.info("RAZI :: removeItems STOP")
                }.onUpdate { List<SyncTask.UpdateItem<Network, Map>> updateItems ->
                    log.info("RAZI :: updateMatchedNetworks START")
                    updateMatchedNetworks(updateItems, networkTypes, subnetType)
                    log.info("RAZI :: updateMatchedNetworks STOP")
                }.onAdd { itemsToAdd ->
                    log.info("RAZI :: addMissingNetworks START")
                    addMissingNetworks(itemsToAdd, networkTypes, subnetType)
                    log.info("RAZI :: addMissingNetworks STOP")
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

    private addMissingNetworks(Collection<Map> addList, List<NetworkType> networkTypes, NetworkSubnetType subnetType) {
        def networkAdds = []
        try {
            addList?.each { cloudItem ->
                def networkType = networkTypes.find{ type -> type.externalType == cloudItem.type }
                log.info("RAZI :: networkType: ${networkType}")
                def networkConfig = [
                        code        : "hyperv.network.${server.cloud.id}.${cloudItem.name}",
                        category    : "hyperv.network.${server.cloud.id}",
                        zone        : server.cloud,
                        dhcpServer  : true,
                        name        : cloudItem.name,
                        externalId  : cloudItem.name,
                        type        : networkType,
                        refType     : 'ComputeZone',
                        refId       : "${server.cloud.id}",
                        owner       : server.cloud.owner,
                        active      : server.cloud.defaultNetworkSyncActive
                ]
                Network networkAdd = new Network(networkConfig)

                def subnetConfig = [
                        account             : server.account,
                        category            : "hyperv.network.${server.cloud.id}.${server.id}",
                        networkSubnetType   : subnetType,
                        code                : "hyperv.network.${server.cloud.id}.${server.id}.${cloudItem.name}",
                        name                : cloudItem.name,
                        externalId          : cloudItem.name,
                        refType             : 'ComputeServer',
                        refId               : server.id,
                        description         : cloudItem.name
                ]
                log.info("RAZI :: subnetConfig: ${subnetConfig}")
                def addSubnet = new NetworkSubnet(subnetConfig)
                morpheusContext.async.networkSubnet.create([addSubnet], networkAdd)
                networkAdds << networkAdd
            }
            //create networks
            log.info("RAZI :: networkAdds: ${networkAdds}")
            morpheusContext.async.cloud.network.create(networkAdds).blockingGet()
            log.info("RAZI :: networkAdds SUCCESS")
        } catch (e) {
            log.error "Error in adding Network sync ${e}", e
        }
    }

    private updateMatchedNetworks(List<SyncTask.UpdateItem<Network, Map>> updateList, List<NetworkType> networkTypes, NetworkSubnetType subnetType) {
        log.debug("NetworkSync:updateMatchedNetworks: Entered")
//        def networkSubnets = morpheusContext.services.networkSubnet.listIdentityProjections(server.cloud.id)
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
//                def existingSubnet = networkSubnets.find{subnet -> subnet.refType == 'ComputeServer' && subnet.refId == server.id}
                def existingSubnet = existingItem.subnets?.find{ subnet -> subnet.refType == 'ComputeServer' && subnet.refId == server.id }
                log.info("RAZI :: existingSubnet: ${existingSubnet}")
                if(!existingSubnet) {
                    def subnetConfig = [
                            account             : server.account,
                            category            : "hyperv.network.${server.cloud.id}.${server.id}",
                            networkSubnetType   : subnetType,
                            code                : "hyperv.network.${server.cloud.id}.${server.id}.${masterItem.name}",
                            name                : masterItem.name,
                            externalId          : masterItem.name,
                            refType             : 'ComputeServer',
                            refId               : server.id,
                            description         : masterItem.name
                    ]
                    def addSubnet = new NetworkSubnet(subnetConfig)
                    morpheusContext.async.networkSubnet.create([addSubnet], existingItem)
                }
                log.info("RAZI :: save: ${save}")

                if (save) {
                    itemsToUpdate << existingItem
                }
            }
            log.info("RAZI :: itemsToUpdate.size(): ${itemsToUpdate.size()}")
            if (itemsToUpdate.size() > 0) {
                morpheusContext.async.cloud.network.save(itemsToUpdate).blockingGet()
            }
        } catch(e) {
            log.error "Error in update Network sync ${e}", e
        }
    }
}