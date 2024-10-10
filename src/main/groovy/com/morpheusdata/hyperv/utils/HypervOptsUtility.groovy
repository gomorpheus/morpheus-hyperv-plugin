package com.morpheusdata.hyperv.utils

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.hyperv.HyperVApiService
import com.morpheusdata.model.Workload
import groovy.util.logging.Slf4j

/**
 * @author razi.ahmad
 */
@Slf4j
class HypervOptsUtility {

    static getAllHypervServerOpts(MorpheusContext context, server) {
        def rtn = getHypervZoneOpts(context, server.cloud)
        rtn += getHypervHypervisorOpts(server.parentServer)
        rtn += getHypervServerOpts(context, server)
        return rtn
    }

    static getHypervServerOpts(MorpheusContext context, server) {
        def zoneConfig = server.cloud.getConfigMap()
        def serverName = server.name //cleanName(server.name)
        def serverConfig = server.getConfigMap()
        def rootVolume = server.volumes?.find{it.rootVolume == true}
        def maxMemory = server.maxMemory ?:server.plan.maxMemory
        def maxCpu = server.maxCpu ?:server.plan?.maxCpu ?:1
        def maxCores = server.maxCores ?:server.plan.maxCores ?:1
        // TODO: below lines are commented for now, need to work on this if its needed.
        /*def maxStorage = getServerRootSize(server)
        def maxTotalStorage = getServerVolumeSize(server)
        def dataDisks = getServerDataDiskList(server)*/
        def network = context.services.network.get(serverConfig.networkId?.toLong())
        def serverFolder = "morpheus_server_${server.id}"
        return [name:serverName, config:serverConfig, server:server, memory:maxMemory, maxCores:maxCores, serverFolder:serverFolder,
                hostname:server.getExternalHostname(), network:network]
//                osDiskSize:maxStorage, maxTotalStorage:maxTotalStorage, dataDisks:dataDisks]
    }

    static getAllHypervWorloadOpts(MorpheusContext context, workload) {
        def rtn = getHypervZoneOpts(context, workload.server.cloud)
        if(workload.server.parentServer) {
            rtn += getHypervHypervisorOpts(workload.server.parentServer)
        }
        rtn += getHypervWorkloadOpts(context, workload)
        return rtn
    }

    static getHypervWorkloadOpts(MorpheusContext context, Workload container) {
        def zoneConfig = container.server.cloud.getConfigMap()
        def serverConfig = container.server.getConfigMap()
        def containerConfig = container.getConfigMap()
        def network = context.services.network.get(containerConfig.networkId?.toLong())
        def serverFolder = "morpheus_server_${container.server.id}"
        def rootVolume = container.server.volumes?.find{it.rootVolume == true}
        def maxMemory = container.maxMemory ?: container.instance.plan.maxMemory
        def maxCpu = container.maxCpu ?: container.instance.plan?.maxCpu ?: 1
        def maxCores = container.maxCores ?: container.instance.plan.maxCores ?: 1
        // TODO: below lines are commented for now, need to work on this if its needed.
        /*def maxStorage = getContainerRootSize(container)
        def maxTotalStorage = getContainerVolumeSize(container)
        def dataDisks = getContainerDataDiskList(container)*/
        def platform = (container.server.serverOs?.platform == 'windows' || container.server.osType == 'windows') ? 'windows' : 'linux'
        return [config:serverConfig, vmId: container.server.externalId, name: container.server.externalId, server:container.server, memory:maxMemory,
                maxCpu:maxCpu, maxCores:maxCores, serverFolder:serverFolder, hostname:container.server.getExternalHostname(), network:network, platform:platform]
    }

    static getHypervZoneOpts(MorpheusContext context, zone) {
        def zoneConfig = zone.getConfigMap()
        def keyPair = context.services.keyPair.find(new DataQuery().withFilter("accountId", zone.account.id))

        return [account:zone.account, zoneConfig:zoneConfig, zone:zone, publicKey:keyPair?.publicKey, privateKey:keyPair?.privateKey]
        // TODO: below line is commented for now, need to work on this if its needed.
//        rpcService:rpcService, commandService:commandService]
       // check: commandService
    }

    static getHypervHypervisorOpts(hypervisor) {
        def serverConfig = hypervisor.getConfigMap()
        log.info("hyperv config: ${serverConfig}")
        def vmRoot = serverConfig.vmPath?.length() > 0 ? serverConfig.vmPath : HyperVApiService.defaultRoot + '\\VMs'
        def diskRoot = serverConfig.diskPath?.length() > 0 ? serverConfig.diskPath : HyperVApiService.defaultRoot + '\\Disks'
        def zoneRoot = serverConfig.workingPath?.length() > 0 ? serverConfig.workingPath : '$HOME/morpheus'
        return [hypervisorConfig:serverConfig, hypervisor:hypervisor, sshHost:hypervisor.sshHost, sshUsername:hypervisor.sshUsername,
                sshPassword:hypervisor.sshPassword, zoneRoot:zoneRoot, diskRoot:diskRoot, vmRoot:vmRoot, sshPort:hypervisor.sshPort]
    }

    static getHypervInitializationOpts(zone) {
        def zoneConfig = zone.getConfigMap()
        def vmRoot = zoneConfig.vmPath?.length() > 0 ? zoneConfig.vmPath : HyperVApiService.defaultRoot + '\\VMs'
        def diskRoot = zoneConfig.diskPath?.length() > 0 ? zoneConfig.diskPath : HyperVApiService.defaultRoot + '\\Disks'
        def zoneRoot = zoneConfig.workingPath?.length() > 0 ? zoneConfig.workingPath : HyperVApiService.defaultRoot
        return [sshHost : zoneConfig.hypervHost, sshPort: zoneConfig.winrmPort ? zoneConfig.winrmPort.toInteger() : null, sshUsername: getUsername(zone), sshPassword: getPassword(zone), zoneRoot: zoneRoot,
                diskRoot: diskRoot, vmRoot: vmRoot]
    }

    static getUsername(zone) {
        zone.accountCredentialData?.username ?: zone.getConfigProperty('username')
    }

    static getPassword(zone) {
        zone.accountCredentialData?.password ?: zone.getConfigProperty('password')
    }
}
