# Morpheus Hyper-V Plugin
This library provides an integration between Microsoft Hyper-V and Morpheus. A `CloudProvider` (for syncing the Cloud related objects), a `ProvisionProvider` (for provisioning into Hyper-V), and a `BackupProvider` (for VM snapshots and backups) are implemented in this plugin.

### Requirements
Microsoft Hyper-V - Version 2016 or greater

### Building
`./gradlew shadowJar`

### Configuration
The following options are required when setting up a Morpheus Cloud to a Hyper-V environment using this plugin:
1. Hyper-V host (i.e. 10.100.10.100)
2. WINRM Port (defaults to 5985)
3. Working Path - A directory on the host for Morpheus to use a working directory
4. VM Path - The Hyper-V location of virtual machines. This should match the setting in Hyper-V.
5. Disk Path - The Hyper-V location of Virtual Hard Disks. This should match the setting in Hyper-V.
3. Username
4. Password

For additional documentation on setting up the cloud refer to the [full documentation](https://docs.morpheusdata.com/en/latest/integration_guides/Clouds/hyperv/hyperv.html#adding-hyper-v-as-a-private-cloud).

#### Features
Cloud sync: hosts, networks, and virtual machines are fetched from Hyper-V and inventoried in Morpheus. Any additions, updates, and removals to these objects are reflected in Morpheus.

Provisioning: Virtual machines can be provisioned from Morpheus.

Backup: VM snapshots can be created and restored from Morpheus.
