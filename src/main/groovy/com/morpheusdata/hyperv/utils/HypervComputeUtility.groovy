package com.morpheusdata.hyperv.utils

import groovy.json.JsonOutput
import groovy.util.logging.Slf4j

/**
 * @author rahul.ray
 */

@Slf4j
class HypervComputeUtility {

    // TODO: need to implement morpheusContext.executeWindowsCommand method during the implementation of User story
    // TODO: as of now executeCommand method is commented in this file
    // TODO: morpheusContext.executeWindowsCommand(String address, Integer port, String username, String password, String command, Boolean noProfile, Boolean elevated)

    static defaultRoot = 'C:\\morpheus'


    static prepareNode(opts) {
        def zoneRoot = opts.zoneRoot ?: defaultRoot
        def command = "mkdir \"${zoneRoot}\\images\""
        def out = executeCommand(command, opts)
        command = "mkdir \"${zoneRoot}\\export\""
        out = executeCommand(command, opts)
    }

    static insertContainerImage(opts) {
        def rtn = [success:false, imageExists:false]
        def zoneRoot = opts.zoneRoot ?: defaultRoot
        def image = opts.image
        def imageName = image.name
        def imageFolderName = formatImageFolder(imageName) // check: formatImageFolder ??
        def tgtFolder = "${zoneRoot}\\images\\${imageFolderName}"
        def match = findImage(opts, imageName)
        log.info("findImage: ${match}")
        if(match.imageExists == false) {
            //transfer it to host
            def transferResults = transferImage(opts, image.cloudFiles, imageName)
            log.debug "transferImage: ${transferResults}"
            if(transferResults.success == true) {
                //clone it to vm folder
                rtn.image = [path:tgtFolder, name:imageName]
                rtn.imageId = tgtFolder
                rtn.success = true
            } else {
                rtn.msg = 'Error transferring image'
            }
        } else {
            rtn.image = [path:tgtFolder, name:match.imageName]
            rtn.imageId = tgtFolder
            rtn.success = true
        }
        return rtn
    }

    static transferImage(morpheusContext, opts, cloudFiles, imageName) {
        def rtn = [success:false, results:[]]
        def metadataFile = cloudFiles?.findAll{cloudFile -> cloudFile.name == 'metadata.json'}
        def vhdFiles = cloudFiles?.findAll{cloudFile -> cloudFile.name.indexOf('.vhd') > -1 || cloudFile.name.indexOf('.vhdx')}
        log.info("vhdFiles: ${vhdFiles}")
        def zoneRoot = opts.zoneRoot ?: defaultRoot
        def imageFolderName = formatImageFolder(imageName) // check: formatImageFolder ??
        def fileList = []
        def tgtFolder = "${zoneRoot}\\images\\${imageFolderName}"
        opts.targetImageFolder = tgtFolder
        def cachePath = opts.cachePath
        def command = "mkdir \"${tgtFolder}\""
        log.debug("command: ${command}")
        def dirResults = morpheusContext.executeCommand(command, opts)

        if(metadataFile) {
            // TODO: move getCloudFileStreamUrl to service class - replace with context.async.virtualImage.getCloudFileStreamUrl
            def tgtUrl //= VirtualImageService.getCloudFileStreamUrl(opts.image.virtualImageId as Long, metadataFile,opts.userId as Long,opts.zone as ComputeZone)
            tgtUrl = tgtUrl.replace("https","http")
            log.debug("metadata url: ${tgtUrl}")
            fileList << [inline:true, action:'download', content:tgtUrl.bytes.encodeAsBase64(),
                         targetPath:"${tgtFolder}\\metadata.json".toString()]
        }
        vhdFiles.each { vhdFile ->
            def tgtFilename = extractImageFileName(vhdFile.name) // check: extractImageFileName ???
            // TODO: move getCloudFileStreamUrl to service class - replace with context.async.virtualImage.getCloudFileStreamUrl
            def tgtUrl //= VirtualImageService.getCloudFileStreamUrl(opts.image.virtualImageId as Long, vhdFile,opts.userId as Long,opts.zone as ComputeZone)
            log.info("vhd url: ${tgtUrl}")
            fileList << [inline:true, action:'download', content:tgtUrl.bytes.encodeAsBase64(),
                         targetPath:"${tgtFolder}\\${tgtFilename}".toString()]
        }
        fileList.each { fileAction ->
            def filePromise = opts.commandService.sendAction(opts.hypervisor, fileAction, [timeout:1800000l])
            def fileResults = filePromise.get(1000l * 60l * 15l)
            rtn.success = fileResults?.success == true
        }

        return rtn
    }

    static cloneImage(opts, srcImage, tgtName) {
        log.info("cloneImage: ${srcImage} -> ${tgtName}")
        def rtn = [success:false]
        try {
            def diskRoot = opts.diskRoot
            def imageFolderName = opts.serverFolder
            def tgtFolder = "${diskRoot}\\${imageFolderName}"
            def command = "mkdir \"${tgtFolder}\""
            def out = executeCommand(command, opts)
            command = "xcopy \"${srcImage}\" \"${tgtFolder}\" /y /i /r /h /s"
            log.debug("cloneImage command: ${command}")
            out = executeCommand(command, opts)
            log.info("cloneImage: ${out}")
            if(out.success == true) {
                command = "dir \"${tgtFolder}\""
                out = executeCommand(command, opts)
                rtn.targetPath = tgtFolder
                rtn.success = out.success
            }
        } catch(e) {
            log.error("cloneImage error: ${e}", e)
        }
        return rtn
    }

    static listImages(opts) {

    }

    static findImage(opts, imageName) {
        def rtn = [success:false, imageExists:false]
        def zoneRoot = opts.zoneRoot ?: defaultRoot
        def imageFolder = formatImageFolder(imageName)
        def imageFolderPath = "${zoneRoot}\\images\\${imageFolder}"
        def command = "dir \"${imageFolderPath}\""
        log.debug("findImage command: ${command}")
        def out = executeCommand(command, opts)
        log.info("findImage: ${out.data}")
        rtn.success = out.success
        if(out.data?.length() > 0) {
            rtn.imageExists = true
            rtn.imageName = imageName
        }
        return rtn
    }

    static createDisk(opts, diskPath, diskSize) {
        def command = "New-VHD -Path \"${diskPath}\" -SizeBytes ${diskSize} -Dynamic"
        log.debug "createDisk command: ${command}"
        return executeCommand(command, opts)
    }

    static attachDisk(opts, vmId, diskPath) {
        def command = "Add-VMHardDiskDrive -VMName \"${vmId}\" -Path \"${diskPath}\""
        log.debug "attachDisk command: ${command}"
        return executeCommand(command, opts)
    }

    static resizeDisk(opts, diskPath, diskSize) {
        def command = "Resize-VHD -Path \"${diskPath}\" -SizeBytes ${diskSize}"
        log.debug "resizeDisk: ${command}"
        return executeCommand(command, opts)
    }

    static detachDisk(opts, vmId, controllerType, controllerNumber, controllerLocation) {
        def command = "Remove-VMHardDiskDrive -VMName \"${vmId}\" -ControllerType \"${controllerType}\" -ControllerNumber \"${controllerNumber}\" -ControllerLocation \"${controllerLocation}\""
        log.debug "detachDisk: ${command}"
        return executeCommand(command, opts)
    }

    static deleteDisk(opts, diskName) {
        def rtn = [success:false]
        try {
            def diskRoot = opts.diskRoot
            def vmFolder = opts.serverFolder
            def diskPath = "${diskRoot}\\${vmFolder}\\${diskName}"
            def command = "Remove-Item -LiteralPath \"${diskPath}\" -Force"
            log.debug "deleteDisk command: ${command}"
            def out = executeCommand(command, opts)
            log.debug "deleteDisk: ${out}"
            rtn.success = out.success && out.exitValue == 0
        } catch(e) {
            log.error("deleteDisk error: ${e}", e)
        }
        return rtn
    }

    static validateServerConfig(Map opts=[:]) {
        log.debug("validateServerConfig: ${opts}")
        def rtn = [success:false, errors:[]]
        try {
            //def zone = ComputeZone.read(opts.zoneId)
            // if(!opts.networkId)
            // 	rtn.errors += [field: 'networkId', msg: 'You must choose a network']
            if(opts.networkInterfaces?.size() > 0) {
                def hasNetwork = true
                opts.networkInterfaces?.each {
                    if(!it.network.group && (it.network.id == null || it.network.id == '')) {
                        hasNetwork = false
                    }
                }
                if(hasNetwork != true) {
                    rtn.errors += [field:'networkInterface', msg:'You must choose a network for each interface']
                }
            } else {
                rtn.errors += [field:'networkInterface', msg:'You must choose a network']
            }
            if(opts.containsKey('hostId') && !opts.hostId) {
                rtn.errors += [field: 'hostId', msg: 'You must choose a host']
                rtn.errors += [field: 'hypervHostId', msg: 'You must choose a host']
            }
            if(opts.containsKey('nodeCount') && !opts.nodeCount) {
                rtn.errors += [field: 'nodeCount', msg: 'Cannot be blank']
                rtn.errors += [field: 'config.nodeCount', msg: 'Cannot be blank']
            }
            rtn.success = (rtn.errors.size() == 0)
            log.debug "validateServer results: ${rtn}"
        } catch(e)  {
            log.error "error in validateServerConfig: ${e}", e
        }
        return rtn
    }

    static updateServer(opts, vmId, updates = [:]) {
        log.info("updateServer: vmId: ${vmId}, updates: ${updates}")
        def rtn = [success:false]
        try {
            if(updates.maxMemory || updates.maxCores || updates.notes) {
                def command = "Set-VM -Name \"${vmId}\""
                if(updates.maxCores)
                    command += " -ProcessorCount ${updates.maxCores}"
                if(updates.maxMemory)
                    command += " -MemoryStartupBytes ${updates.maxMemory}"
                if(updates.notes) {
                    command += " -Notes ${updates.notes}"
                }

                log.debug "updateServer: ${command}"
                def out = executeCommand(command, opts)
                log.debug "updateServer results: ${out}"
                rtn.success = out.success && out.exitValue == 0
            } else {
                log.info("No updates for server: ${vmId}")
                rtn.success = true
            }
        } catch(e)  {
            log.error "updateServer error: ${e}", e
        }
        return rtn
    }

    static cloneServer(opts) {
        log.debug "cloneServer opts: ${opts}"
        def rtn = [success:false]
        try {
            def imageName = opts.imageId
            def cloneResults = cloneImage(opts, imageName, opts.serverFolder)
            log.info "cloneResults: ${cloneResults}"
            if(cloneResults.success == true) {
                def disks = [osDisk:[:], dataDisks:[]]
                def diskRoot = opts.diskRoot
                def vmRoot = opts.vmRoot
                def imageFolderName = opts.serverFolder
                def networkName = opts.network?.name
                def diskFolder = "${diskRoot}\\${imageFolderName}"
                def bootDiskName = opts.diskMap?.bootDisk?.fileName ?: 'ubuntu-14_04.vhd'
                disks.osDisk = [externalId:bootDiskName]
                def osDiskPath = diskFolder + '\\' + bootDiskName
                def vmFolder = "${vmRoot}\\${imageFolderName}"
                //network config
                def additionalNetworks = []
                if(opts.networkConfig?.primaryInterface?.network?.externalId) { //new style multi network
                    def primaryInterface = opts.networkConfig.primaryInterface
                    networkName = primaryInterface.network.externalId
                    //additional nics
                    def extraIndex = 1
                    opts.networkConfig.extraInterfaces?.each { extraInterface ->
                        log.debug("Provisioning extra interface ${extraInterface}")
                        if(extraInterface.network?.externalId) {
                            additionalNetworks << [switchName: extraInterface.network.externalId, name: "${opts.name} NIC${extraIndex}", vlanId: extraInterface.network.vlanId]
                            extraIndex++
                        }
                    }
                }
                Integer generation = 1
                if(osDiskPath.endsWith('.vhdx')) {
                    generation = 2
                }
                def launchCommand = "New-VM -Name \"${opts.name}\" -MemoryStartupBytes ${opts.memory} -Generation ${generation} -VHDPath \"${osDiskPath}\" " +
                        "-BootDevice VHD -Path \"${vmFolder}\" -SwitchName \"${networkName}\" " //-ComputerName ${opts.name}
                //Parameter Set: Existing VHD
                //New-VM [[-Name] <String> ] [[-MemoryStartupBytes] <Int64> ] [[-Generation] <Int16> ] -VHDPath <String> [-AsJob]
                //[-BootDevice <BootDevice> {CD | Floppy | LegacyNetworkAdapter | IDE | NetworkAdapter | VHD} ] [-ComputerName <String[]> ]
                //[-Path <String> ] [-SwitchName <String> ] [-Confirm] [-WhatIf] [ <CommonParameters>]
                //run it
                log.info("launchCommand: ${launchCommand}")
                def out = executeCommand(launchCommand, opts)


                log.debug("run server: ${out}")
                if(out.success == true) {
                    //we need to fix SecureBoot
                    String secureBootCommand
                    if(opts.secureBoot) {
                        secureBootCommand = "Set-VMFirmware \"${opts.name}\" -EnableSecureBoot On"
                    } else {
                        secureBootCommand = "Set-VMFirmware \"${opts.name}\" -EnableSecureBoot Off"
                    }
                    executeCommand(secureBootCommand, opts)
                    //if we have to tag it to a VLAN
                    if(opts.networkConfig.primaryInterface.network.vlanId) {
                        String setVlanCommand = "Set-VMNetworkAdapterVlan -VMName \"${opts.name}\" -Access -VlanId ${opts.networkConfig.primaryInterface.network.vlanId}"
                        executeCommand(setVlanCommand, opts)
                    }
                    //add additional NICS
                    if(additionalNetworks) {
                        additionalNetworks.each{additionalNetwork ->
                            def addNetworkCommand = "Add-VMNetworkAdapter -VMName \"${opts.name}\" -Name \"${additionalNetwork.name}\" -SwitchName \"${additionalNetwork.switchName}\""
                            executeCommand(addNetworkCommand, opts)
                            if(additionalNetwork.vlanId) {
                                String setVlanCommand = "Set-VMNetworkAdapterVlan -VMName \"${opts.name}\" -VMNetworkAdapterName \"${additionalNetwork.name}\" -Access -VlanId ${additionalNetwork.vlanId}"
                                executeCommand(setVlanCommand, opts)
                            }
                        }
                    }
                    //resize disk
                    if(opts.osDiskSize)
                        resizeDisk(opts, osDiskPath, opts.osDiskSize)
                    //add disk
                    if(opts.dataDisks?.size() > 0) {
                        opts.dataDisks?.eachWithIndex { disk, index ->
                            def diskIndex = "${index + 1}"
                            def dataDisk = "dataDisk${diskIndex}.vhd"
                            if(generation == 2) {
                                dataDisk = "dataDisk${diskIndex}.vhdx"
                            }

                            def newDiskPath = "${diskFolder}\\${dataDisk}"
                            //if this is a clone/restore we have already copied the disk, otherwise need to create it
                            if(!opts.snapshotId) {
                                createDisk(opts, newDiskPath, disk.maxStorage)
                            }
                            attachDisk(opts, opts.name, newDiskPath)
                            disk.externalId = dataDisk
                            disks.dataDisks << disk
                        }
                    } else if(opts.dataDiskSize) {
                        disks << [dataDisks:[]]
                        def dataDisk = "dataDisk1.vhd"
                        def newDiskPath = "${diskFolder}\\${dataDisk}"
                        //if this is a clone/restore we have already copied the disk, otherwise need to create it
                        if(!opts.snapshotId) {
                            createDisk(opts, newDiskPath, opts.dataDiskSize)
                        }
                        attachDisk(opts, opts.name, newDiskPath)
                    }
                    //cpu
                    if(opts.maxCores && opts.maxCores > 0) {
                        updateServer(opts, opts.name, [maxCores:opts.maxCores])
                    }
                    enableDynamicMemory(opts)

                    //need to add non boot disks from the diskMap - TODO
                    //cloud init
                    if(opts.cloudConfigBytes) {
                        def isoAction = [inline:true, action:'rawfile', content:opts.cloudConfigBytes.encodeAsBase64(), targetPath:"${diskFolder}\\config.iso".toString(), opts:[:]]
                        def isoPromise = opts.commandService.sendAction(opts.hypervisor, isoAction)
                        def isoResults = isoPromise.get(1000l * 60l * 3l)
                        if(generation == 2) {
                            createCdrom(opts, opts.name, "${diskFolder}\\config.iso")
                        }
                        else {
                            setCdrom(opts, opts.name, "${diskFolder}\\config.iso")
                        }
                    }
                    //start it
                    sleep(10000) // just a test
                    log.info("Starting Server  ${opts.name}")
                    startServer(opts, opts.name)
                    //get details
                    log.info("Hyperv Check for Server Ready ${opts.name}")
                    def serverDetail = checkServerReady(opts, opts.name)
                    if(serverDetail.success == true) {
                        // write ip address to notes here
                        updateServer(opts, opts.name, [notes: serverDetail.server?.ipAddress])
                        rtn.server = [name:opts.name, serverFolder:vmFolder, id:opts.name, ipAddress:serverDetail.server?.ipAddress, disks:disks, externalId: opts.name, vmId: serverDetail.server?.vmId]
                        rtn.success = true
                    } else {
                        rtn.server = [name:opts.name, serverFolder:vmFolder, id:opts.name, ipAddress:serverDetail.server?.ipAddress, disks:disks]
                    }
                }
            }
        } catch(e) {
            log.error("cloneServer error: ${e}", e)
        }
        return rtn
    }

    static createCdrom(opts, vmName, cdPath) {
        def command = "Add-VMDvdDrive -VMName \"${vmName}\" –Path \"${cdPath}\""
        return executeCommand(command, opts)
    }

    static setCdrom(opts, vmName, cdPath) {
        def command = "Set-VMDvdDrive -VMName \"${vmName}\" –Path \"${cdPath}\""
        return executeCommand(command, opts)
    }

    static removeCdrom(opts) {
        //ide1:0.devicetype = "cdrom-raw"
        //ide1:0.filename = "auto detect"
        //ide1:0.present = “FALSE”
    }

    static getHypervHost(opts) {
        def rtn = [success:false]
        def command = "Get-VMHost | Format-List"
        log.debug("getHypervHost command: ${command}")
        def results = executeCommand(command, opts)
        log.debug("getHypervHost: ${results}")
        if(results.success == true && results.exitValue == 0) {
            rtn.host = parseHypervListData(results.data)
            rtn.success = true
        }
        log.debug("getHypervHost: ${rtn}")
        return rtn
    }

    static getHypervServerInfo(opts) {
        def rtn = [success:false]
        def command = 'hostname'
        def out = executeCommand(command, opts)
        log.debug("out: ${out.data}")
        rtn.hostname = cleanData(out.data)
        command = 'wmic computersystem get TotalPhysicalMemory'
        out = executeCommand(command, opts)
        log.debug("out: ${out.data}")
        rtn.memory = cleanData(out.data, 'TotalPhysicalMemory')
        command = 'wmic diskdrive get size'
        out = executeCommand(command, opts)
        log.debug("out: ${out.data}")
        rtn.disks = cleanData(out.data, 'Size')
        rtn.success = true
        return rtn
    }

    static getServerDisks(opts, vmId) {
        def rtn = [success:false]
        def diskData = []
        def command = "Get-VMHardDiskDrive -VMName \"${vmId}\" | Format-List ControllerType, ControllerNumber, ControllerLocation, Id, Path"
        log.debug("getServerDisks command: ${command}")
        def results = executeCommand(command, opts)
        log.debug("getServerDisks: ${results}")
        if(results.success == true && results.exitValue == 0) {
            def diskResults = results.data?.split("\n")
            diskResults.each { diskResult ->
                if(diskResult.length() > 0){
                    def diskInfo = parseDiskDetails(diskResult)
                    if(diskInfo?.success) diskData << diskInfo?.disk
                }
            }
            rtn.disks = diskData
            rtn.success = true
        }
        log.info("getServerDisks: ${rtn}")
        return rtn
    }

    static getHypervMemory(opts) {

    }

    static listVmSwitches(opts) {
        log.debug "listVmSwitches opts: ${opts}"
        def rtn = [success:false, bridgeList:[]]
        def command = 'Get-VMSwitch | Format-Table'
        def results = executeCommand(command, opts)
        log.debug("results: ${results}")
        rtn.vmSwitchList = parseVmSwitchList(results.data)
        log.debug "vmSwitchList?.size(): ${rtn.vmSwitchList?.size()}"
        rtn.success = results.success
        return rtn
    }

    static checkServerReady(opts, vmId) {
        def rtn = [success:false]
        try {
            def pending = true
            def attempts = 0
            while(pending) {
                sleep(1000l * 5l)
                def serverDetail = getServerDetails(opts, vmId)
                if(serverDetail.success == true) {
                    if(serverDetail.server.ipAddress) {
                        rtn.success = true
                        rtn.server = serverDetail.server
                        pending = false
                    } else {
                        opts.server.refresh()
                        log.debug("check server loading server: ip: ${opts.server.internalIp}")
                        if(opts.server.internalIp) {
                            rtn.success = true
                            rtn.server = serverDetail.server
                            rtn.server.ipAddress = opts.server.internalIp
                            pending = false
                        }
                    }
                }
                attempts ++
                if(attempts > 100)
                    pending = false
            }
        } catch(e) {
            log.error("An Exception Has Occurred",e)
        }
        return rtn
    }

    static getServerDetails(opts, vmId) {
        def rtn = [success:false]
        try {
            def command = "Get-VM -Name \"${vmId}\" | Format-List VMname, VMID, Status, Uptime, State, CpuUsage, MemoryAssigned, ComputerName"
            def results = executeCommand(command, opts)
            if(results.success == true && results.exitValue == 0) {
                def vmData = parseVmDetails(results.data)
                if(vmData.success == true) {
                    command = "Get-VMNetworkAdapter -VMName \"${vmId}\" | Format-List"
                    results = executeCommand(command, opts)
                    if(results.success == true && results.exitValue == 0) {
                        log.debug("network data: ${results.data}")
                        def vmNetworkData = parseVmNetworkDetails(results.data)
                        //parse it
                        rtn.server = vmData + vmNetworkData
                        rtn.success = true
                    }
                }
            }

        } catch(e){
            log.error("An Exception Has Occurred for getServerDetails: ${e.message}",e)
        }
        log.debug("getServerDetails: ${rtn}")
        return rtn
    }

    static listVirtualMachines(opts) {
        def rtn = [success:false, virtualMachines: []]


        def hasMore = true
        def pageSize = 50
        def fetch = { offset ->

            def commandStr = """\$report = @()"""
            commandStr += """
	\$VMs = Get-VM | Sort-Object -Property ID | Select-Object -Skip $offset -First $pageSize
	"""
            commandStr += """
				foreach (\$VM in \$VMs) {
					\$data = New-Object PSObject -property @{
						ID=\$VM.Id
						Name=\$VM.Name
						CPUCount=\$VM.ProcessorCount
						Memory=\$VM.MemoryMaximum
						State=\$VM.State
						MemoryDemand=\$VM.MemoryDemand
						CPUUsage=\$VM.CPUUsage
						Generation=\$VM.Generation
						TotalSize=0
						UsedSize=0
						Disks=@()
						IpAddress=''
						InternalIp=''
						HostName=\$VM.ComputerName
						NetworkAdapter=''
						Notes=\$VM.Notes
					}
		
					\$VHDs = \$VM | Get-VMHardDiskDrive
					foreach (\$VHD in \$VHDs){
						\$VHDInfo = Get-VHD -Path \$VHD.Path
						\$disk = New-Object PSObject -property @{
							ID=\$VHD.Id
							Name=\$VHD.Name
							Path=\$VHD.Path
							TotalSize=\$VHDInfo.Size
							ControllerType=\$VHD.ControllerType
							ControllerNumber=\$VHD.ControllerNumber
							ControllerLocation=\$VHD.ControllerLocation
						}

						\$data.Disks += \$disk
						\$data.TotalSize += \$VHDInfo.Size
					}

					\$VNAs = \$VM | Get-VMNetworkAdapter
					foreach (\$VNA in \$VNAs) {
						foreach (\$ip in \$VNA.IPAddresses) {
							if([string]::IsNullOrEmpty(\$data.IpAddress)) {
								\$data.IpAddress = \$ip
								\$data.InternalIp = \$ip
							}
						}
						\$data.NetworkAdapter = \$VNA.SwitchName
					}

					\$report +=\$data
				}
				\$report """

            def command = generateCommandString(commandStr)
            def out = wrapExecuteCommand(command, opts)
            log.debug("out: ${out.data}")
            if (out.success) {
                hasMore = out.data != ''
                if(out.data) {
                    rtn.virtualMachines += out.data
                }
                rtn.success = true
            } else {
                hasMore = false
            }
        }

        def currentOffset = 0
        while(hasMore) {
            fetch(currentOffset)
            currentOffset += pageSize
        }

        return rtn
    }


    static generateCommandString(command) {
        // FormatEnumeration causes lists to show ALL items
        // width value prevents wrapping
        "\$FormatEnumerationLimit =-1; ${command} | ConvertTo-Json -Depth 3"
    }

    static wrapExecuteCommand(String command, Map opts = [:]) {
        def out = executeCommand(command, opts)
        if(out.data) {
            def payload = out.data
            if (!out.data.startsWith('[')) {
                payload = "[${out.data}]"
            }
            try {
                log.debug "Received: ${JsonOutput.prettyPrint(payload)}"
            } catch (e) {
                File file = new File("/Users/bob/Desktop/bad.json")
                file.write payload
            }
            out.data = new groovy.json.JsonSlurper().parseText(payload)
        }
        out
    }

    static removeServer(opts, vmId) {
        def rtn = [success:false]
        try {
            def command = "Remove-VM –Name \"${vmId}\" -Force"
            def out = executeCommand(command, opts)
            rtn.success = out.success && out.exitValue == 0
            println("remove server: ${out}")
        } catch(e) {
            log.error("removeServer error: ${e}", e)
        }
        return rtn
    }

    static deleteServer(opts) {
        def rtn = [success:false]
        try {
            def zoneRoot = opts.zoneRoot ?: defaultRoot
            def diskRoot = opts.diskRoot
            def vmRoot = opts.vmRoot
            def imageFolderName = opts.serverFolder
            def tgtFolder = "${diskRoot}\\${imageFolderName}"
            def tgtFolderVm = "${vmRoot}\\${imageFolderName}"
            def command = """\$ignore = Remove-Item -LiteralPath \"${tgtFolder}\" -Recurse -Force
							\$ignore = Remove-Item -LiteralPath \"${tgtFolderVm}\" -Recurse -Force"""
            def out = wrapExecuteCommand(generateCommandString(command), opts)
            println("delete server: ${out}")
            rtn.success = true
        } catch(e) {
            log.error("deleteServer error: ${e}", e)
        }
        return rtn
    }

    static stopServer(opts, vmId) {
        def rtn = [success:false]
        try {
            def command = "Stop-VM -Name \"${vmId}\" -Force${opts.turnOff ? ' -TurnOff' :''}"
            def out = executeCommand(command, opts)
            rtn.success = out.success && out.exitValue == 0
            println("stop server: ${out}")
        } catch(e) {
            log.error("startServer error: ${e}", e)
        }
        return rtn
    }

    static startServer(opts, vmId) {
        def rtn = [success:false]
        try {
            def command = "Start-VM -Name \"${vmId}\""
            def out = executeCommand(command, opts)
            rtn.success = out.success
        } catch(e) {
            log.error("startServer error: ${e}", e)
        }
        return rtn
    }

    static pauseServer(opts, vmId) {
        def rtn = [success:false]
        try {
            def command = "/Applications/VMware\\ Fusion.app/Contents/Library/vmrun -T fusion pause ${vmId}"
            def out = executeCommand(command, opts)
            rtn.success = out.success
        } catch(e) {
            log.error("startServer error: ${e}", e)
        }
        return rtn
    }

    static resumeServer(opts, vmId) {
        def rtn = [success:false]
        try {
            def command = "/Applications/VMware\\ Fusion.app/Contents/Library/vmrun -T fusion unpause ${vmId}"
            def out = executeCommand(command, opts)
            rtn.success = out.success
        } catch(e) {
            log.error("startServer error: ${e}", e)
        }
        return rtn
    }

    static restoreServer(opts, vmId, snapshotId) {
        def rtn = [success:false]
        try {
            def command = "Restore-VMSnapshot -Name \"${snapshotId}\" -VMName \"${vmId}\" -Confirm:\$false"
            def out = executeCommand(command, opts)
            rtn.success = out.success && out.exitValue == 0
            log.debug("restore server: ${out}")
        } catch(e) {
            log.error("restoreServer error: ${e}")
        }

        return rtn
    }

    static snapshotServer(opts, vmId) {
        def rtn = [success:false]
        try {
            def snapshotId = opts.snapshotId ?: "${vmId}.${System.currentTimeMillis()}"
            def command = "Checkpoint-VM -Name \"${vmId}\" -SnapshotName \"${snapshotId}\""
            def out = executeCommand(command, opts)
            rtn.success = out.success && out.exitValue == 0
            rtn.snapshotId = snapshotId
            log.debug("snapshot server: ${out}")
        } catch(e) {
            log.error("snapshotServer error: ${e}")
        }

        return rtn
    }

    static deleteSnapshot(opts, vmId, snapshotId) {
        def rtn = [success:false]
        try {
            def command = "Remove-VMSnapshot -VMName \"${vmId}\" -Name \"${snapshotId}\""
            def out = executeCommand(command, opts)
            rtn.success = out.success && out.exitValue == 0
            if(!rtn.success) {
                if(out.errorOutput?.contains("Hyper-V was unable to find a virtual machine with")){
                    // Don't fail if the Snapshot isn't there
                    rtn.success = true
                }
            }
            rtn.snapshotId = snapshotId
            log.debug("delete snapshot: ${out}")
        } catch(e) {
            log.error("deleteSnapshot error: ${e}")
        }

        return rtn
    }

    static listSnapshots(opts, vmId) {
        def rtn = [success:false]
        try {
            def command = "Get-VMSnapshot -VMName \"${vmId}\" | Format-Table"
            def out = executeCommand(command, opts)
            rtn.success = out.success && out.exitValue == 0
            log.debug("list snapshots: ${out}")
        } catch(e) {
            log.error("listSnapshots error: ${e}")
        }
    }

    static exportSnapshot(opts, vmId, snapshotId) {
        log.debug("export snapshot vmId: ${vmId}, snapshotId: ${snapshotId}")
        def rtn = [success:false]
        try {
            def zoneRoot = opts.zoneRoot ?: defaultRoot
            def snapshotFolder = formatImageFolder(snapshotId)
            def tgtFolder = "${zoneRoot}\\export\\${snapshotFolder}"
            def command = "mkdir \"${tgtFolder}\""
            def out = executeCommand(command, opts)
            command = "Export-VMSnapshot -Name \"${snapshotId}\" -VMName \"${vmId}\" -Path \"${tgtFolder}\""
            out = executeCommand(command, opts)
            rtn.success = out.success && out.exitValue == 0
            log.debug("export snapshot: ${out}")
            if(rtn.success){
                rtn.diskPath = "${tgtFolder}\\${vmId}\\Virtual Hard Disks"
                rtn.vmPath = "${tgtFolder}\\${vmId}\\Virtual Machines"
            }
        } catch(e) {
            log.error("exportSnapshot error: ${e}")
        }

        return rtn
    }

    static exportVm(opts, vmId) {
        def rtn = [success:false]
        try {
            def zoneRoot = opts.zoneRoot ?: defaultRoot
            def vmFolder = formatImageFolder(vmId)
            def tgtFolder = "${zoneRoot}\\export\\${vmFolder}"
            def command = "mkdir \"${tgtFolder}\""
            def out = executeCommand(command, opts)
            command = "Export-VM -Name \"${vmId}\" -Path \"${tgtFolder}\""
            out = executeCommand(command, opts)
            rtn.success = out.success && out.exitValue == 0
            log.debug("export vm: ${out}")
        } catch(e) {
            log.error("exportVm error: ${e}")
        }

        return rtn
    }

    static deleteExport(opts, snapshotId) {
        def rtn = [success:false]
        try {
            def zoneRoot = opts.zoneRoot ?: defaultRoot
            def exportFolder = formatImageFolder(snapshotId)
            def tgtFolder = "${zoneRoot}\\export\\${exportFolder}"
            def command = "Remove-Item -LiteralPath \"${tgtFolder}\" -Recurse -Force"
            def out = executeCommand(command, opts)
            log.debug("delete export: ${out}")
            rtn.success = out.success && out.exitValue == 0
        } catch(e) {
            log.error("deleteExport error: ${e}")
        }

        return rtn
    }

    //create a new VM from an existing VM or snapshot export
    static importVm(opts, sourceVmId, snapshotId=null) {
        def rtn = [success:false]
        try {
            def diskRoot = opts.diskRoot
            def vmRoot = opts.vmRoot
            def imageFolderName = opts.serverFolder
            def diskFolder = "${diskRoot}\\${imageFolderName}"
            def vmFolder = "${vmRoot}\\${imageFolderName}"

            def zoneRoot = opts.zoneRoot ?: defaultRoot
            def exportSource = snapshotId ?: sourceVmId
            def sourceFolderName = formatImageFolder(exportSource)
            def sourceFolder = "${zoneRoot}\\export\\${sourceFolderName}\\${sourceVmId}\\Virtual Machines"
            //get the vm config xml file from the export directory
            def command = "(dir \"${sourceFolder}\" -filter *.xml).FullName"
            def out = executeCommand(command, opts)
            if(out.success && out.data?.length() > 0) {
                def vmConfigPath = out.data?.trim()
                command = "Import-VM -Path \"${vmConfigPath}\" -Copy -GenerateNewId -VirtualMachinePath \"${vmFolder}\" -VhdDestinationPath \"${diskFolder}\""
                out = executeCommand(command, opts)
                rtn.success = out.success && out.exitValue == 0
                log.debug("import vm: ${out}")
            }
        } catch(e) {
            log.error("importVm error: ${e}")
        }

        return rtn
    }

    static parseVmSwitchList(data) {
        def rtn = []
        def lines = data?.tokenize('\n')
        lines = lines?.findAll{it.length() > 1}
        log.debug("lines: ${lines}")
        if(lines?.size() > 1) {
            def headerMap = parseHypervHeader(lines[1])
            if(headerMap?.count > 2) {
                lines.eachWithIndex { line, index ->
                    if(index > 1) {
                        line = line.trim()
                        def values = []
                        headerMap.columns?.each { col ->
                            def value = ''
                            if(line.length() > col.start) {
                                if(col.end > 0 && col.end < line.length())
                                    value = line.substring(col.start, col.end).trim()
                                else
                                    value = line.substring(col.start).trim()
                            }
                            values << value
                        }
                        def vmSwitch = [name:values[0], type:values[1], interface:values[2]]
                        rtn << vmSwitch
                    }
                }
            }
            log.debug("vmSwitchList: ${rtn}")
        }
        return rtn
    }

    static parseVmNetworkDetails(data) {
        //Get-VMNetworkAdapter -VMName bw-hyperv-node-2
        //Name            IsManagementOs VMName           SwitchName MacAddress   Status IPAddresses
        //----            -------------- ------           ---------- ----------   ------ -----------
        //Network Adapter False          bw-hyperv-node-2 wheeler    00155D2D5B15 {Ok}   {}
        def rtn = [success:false]
        def vmData = parseHypervListData(data)
        if(vmData) {
            rtn.device = vmData.Name
            rtn.managementOs = vmData.IsManagementOs
            rtn.name = vmData.VMName
            rtn.switchName = vmData.SwitchName
            rtn.macAddress = vmData.MacAddress
            rtn.status = vmData.Status
            rtn.ipAddressList = vmData.IPAddresses
            def ipParse = parseIpAddressList(rtn.ipAddressList)
            if(ipParse.ipv6address)
                rtn.ipv6address = ipParse.ipv6address
            if(ipParse.ipAddress)
                rtn.ipAddress = ipParse.ipAddress
            rtn.success = true
        }
        return rtn
    }

    static parseIpAddressList(data) {
        def rtn = [:]
        if(data?.length() > 2) {
            data = data.substring(1, data.length() - 1)
            if(data?.length() > 0) {
                def ipList = data.tokenize(',')
                ipList?.each { ip ->
                    if(ip.indexOf(':') > -1) {
                        rtn.ipv6address = ip.trim()
                    } else if(ip.indexOf('.') > -1) {
                        def newIp = ip.trim()
                        if(!newIp.startsWith('169.'))
                            rtn.ipAddress = ip.trim()
                    }
                }
            }
        }
        return rtn
    }

    static parseVmDetails(data) {
        //Name             State   CPUUsage(%) MemoryAssigned(M) Uptime     Status
        //----             -----   ----------- ----------------- ------     ------
        //bw-hyperv-node-2 Running 0           2048              1.00:51:05 Operating normally
        def rtn = [success:false]
        def vmData = parseHypervListData(data)
        if(vmData) {
            rtn.name = vmData.Name
            rtn.powerState = vmData.State
            rtn.cpuUsage = vmData.CpuUsage
            rtn.memory = vmData.MemoryAssigned
            rtn.uptime = vmData.Uptime
            rtn.status = vmData.Status
            rtn.vmId = vmData.VMId
            rtn.hostName = vmData.ComputerName
            rtn.success = true
        }
        return rtn
    }

    static parseDiskDetails(data) {
        def rtn = [success:false]
        def disk = [:]
        def diskData = parseHypervListFormatData(data)
        if(diskData) {
            disk.controllerType = diskData.ControllerType
            disk.controllerNumber = diskData.ControllerNumber
            disk.controllerLocation = diskData.ControllerLocation
            disk.path = diskData.Path
            disk.id = diskData.Id
            rtn.disk = disk
            rtn.success = true
        }

        return rtn
    }

    static parseHypervData(data) {
        def rtn = []
        def lines = data?.tokenize('\n')
        lines = lines?.findAll{it.length() > 1}
        log.debug("lines: ${lines}")
        if(lines?.size() > 1) {
            def headerMap = parseHypervHeader(lines[1])
            lines.eachWithIndex { line, index ->
                if(index > 1) {
                    line = line.trim()
                    def values = []
                    headerMap.columns?.each { col ->
                        def value = ''
                        if(line.length() > col.start) {
                            if(col.end > 0 && col.end < line.length())
                                value = line.substring(col.start, col.end).trim()
                            else
                                value = line.substring(col.start).trim()
                        }
                        values << value
                    }
                    rtn << values
                }
            }
            log.debug("parseHypervData: ${rtn}")
        }
        return rtn
    }

    static parseHypervHeader(line) {
        def rtn = [count:0, columns:[]]
        if(line?.length() > 0) {
            def lastStart = 0
            def lastEnd = 0
            def more = true
            while(more) {
                def nextSpace = line.indexOf(' ', lastEnd)
                if(nextSpace > -1) {
                    def nextDash = line.indexOf('-', nextSpace)
                    if(nextDash > -1) {
                        lastEnd = nextDash
                        rtn.columns << [start:lastStart, end:lastEnd]
                        lastStart = lastEnd
                    } else {
                        lastEnd = line.length()
                        rtn.columns << [start:lastStart, end:-1]
                        more = false
                    }
                } else {
                    lastEnd = line.length()
                    rtn.columns << [start:lastStart, end:-1]
                    more = false
                }
            }
            rtn.count = rtn.columns.size()
        }
        return rtn
    }

    static parseHypervListData(data) {
        def rtn = [:]
        def lines = data?.tokenize('\n')
        lines = lines?.findAll{it.length() > 1}
        log.debug("lines: ${lines}")
        if(lines?.size() > 1) {
            lines.eachWithIndex { line, index ->
                if(line.indexOf(':') > -1) {
                    def lineTokens = line.tokenize(':')
                    rtn[lineTokens[0].trim()] = lineTokens[1].trim()
                }
            }
            log.debug("parseHypervListData: ${rtn}")
        }
        return rtn
    }

    static parseHypervListFormatData(data) {
        def rtn = [:]
        def lines = data?.tokenize("\n")
        lines = lines?.findAll{it.length() > 1}
        //log.info("lines: ${lines}")
        if(lines?.size() > 1) {
            def key
            def val = ""
            lines.eachWithIndex { line, index ->
                if(line.indexOf(":") > -1) {
                    def lineTokens = line.split(":", 2)
                    key = lineTokens[0].trim()
                    val = lineTokens[1]?.trim() ?: ""
                    rtn[key] = val
                } else {
                    //line was separated by newline in results, append it
                    def lineTokens = [line]
                    val += lineTokens[0].trim()
                    if(key) rtn[key] = val
                }
            }
            log.debug("parseHypervListFormatData: ${rtn}")
        }
        return rtn
    }

    static cleanData(data, ignoreString = null) {
        def rtn = ''
        def lines = data?.tokenize('\n')
        lines = lines?.findAll{it?.trim()?.length() > 1}
        if(lines?.size() > 0) {
            lines?.each { line ->
                def trimLine = line.trim()
                if(rtn == null && ignoreString == null || trimLine != ignoreString)
                    rtn = trimLine
            }
        }
        return rtn
    }

    static void enableDynamicMemory(Map opts){
        String stopCommand = "Stop-VM -Name \"${opts.name}\""
        executeCommand(stopCommand, opts)
        String enableDynamic = "Set-VMMemory \"${opts.name}\" -DynamicMemoryEnabled \$True -MinimumBytes 512MB -MaximumBytes ${opts.memory}"
        executeCommand(enableDynamic, opts)
        String startVM = "Start-VM -Name \"${opts.name}\""
        executeCommand(startVM, opts)
    }
}
