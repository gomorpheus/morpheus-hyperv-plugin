package com.morpheusdata.hyperv.utils

import groovy.util.logging.Slf4j

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * @author rahul.ray
 */
@Slf4j
class VhdUtility {

    private static final int VHD_DISK_SIZE_OFFSET = 40
    private static final int VHD_DISK_SIZE_LENGTH = 8
    private static final int VHD_DISK_TYPE_OFFSET = 60
    private static final int VHD_DISK_TYPE_LENGTH = 4

    private static final int VHD_DISK_TYPE_FIXED = 2
    private static final int VHD_DISK_TYPE_DYNAMIC = 3

    /**
     * Extract the VHD virtual size or approximate the disk size from the size of the file. VHD meta data referenced
     * from https://github.com/libyal/libvhdi/blob/main/documentation/Virtual%20Hard%20Disk%20%28VHD%29%20image%20format.asciidoc
     * @param tarStream containing the VHD file
     * @return the real or approximate size of the disk
     * @throws IOException
     */
    static Long extractVhdDiskSize(InputStream inputStream) throws IOException {
        Long rtn = 0L;

        //TarArchiveEntry tarEntry = tarStream.getNextTarEntry()
       /* if (tarEntry == null) {
            throw new IOException("No tar entry found")
        }*/

        BufferedInputStream bufferedStream
        try {
            // The metadata is in the first 512 bytes
            bufferedStream = new BufferedInputStream(inputStream, 512)
            int totalOffset = 0
            int currentOffset = 0

            // get the offset relative to the current offset
            currentOffset = (VHD_DISK_SIZE_OFFSET - totalOffset)
            totalOffset = currentOffset + VHD_DISK_SIZE_LENGTH
            // Read disk size
            Long diskSize = getHeaderValueLong(bufferedStream, currentOffset, VHD_DISK_SIZE_LENGTH)
            log.debug("extractVhdDiskSize diskSize: ${diskSize}")

            // Get the disk type to determine if the proper headers are in the VHD (may not be needed)
            // get the offset relative to the current offset
            currentOffset = (VHD_DISK_TYPE_OFFSET - totalOffset)

            totalOffset += currentOffset + VHD_DISK_TYPE_LENGTH
            // // Read disk type
            int diskType = getHeaderValueInt(bufferedStream, currentOffset, VHD_DISK_TYPE_LENGTH)
            log.debug("extractVhdDiskSize diskType: ${diskType}")

            // the VHD file was formatted as expected and contained a disk type in the file header
            if(diskSize && diskSize != 0 && (diskType == VHD_DISK_TYPE_FIXED || diskType == VHD_DISK_TYPE_DYNAMIC)) {
                rtn = diskSize
            } /*else {
                log.debug("extractVhdDiskSize disk size not found, getting size from tar entry.")
                // unable to locate the disk size in the VHD header, default to the actual file size
                rtn = tarEntry.getSize()
                log.debug("extractVhdDiskSize tar entry size: ${rtn}")

            }*/
        } finally {
            bufferedStream?.close()
        }
        return rtn
    }

    /**
     * Get a integer value from the header
     * @param bufferedStream a buffered input stream of the VHD file
     * @param offset offset of the value in the meta data
     * @param length length of the value (usually 4 or 8)
     * @return int
     */
    private static int getHeaderValueInt(BufferedInputStream bufferedStream, offset, length) {
        return getHeaderValue(bufferedStream, offset, length).getInt()
    }

    /**
     * Get a Long value from the header
     * @param bufferedStream a buffered input stream of the VHD file
     * @param offset offset of the value in the meta data
     * @param length length of the value (usually 4 or 8)
     * @return Long
     */
    private static Long getHeaderValueLong(BufferedInputStream bufferedStream, offset, length) {
        return getHeaderValue(bufferedStream, offset, length).getLong()
    }

    /**
     * Get a header value
     * @param bufferedStream a buffered input stream of the VHD file
     * @param offset offset of the value in the meta data
     * @param length length of the value (usually 4 or 8)
     * @return ByteBuffer with the header value
     */
    private static ByteBuffer getHeaderValue(BufferedInputStream bufferedStream, Integer offset, Integer length) {
        Long hexOffset = hexOffset(offset)
        bufferedStream.skip(hexOffset)
        // Read the bytes that represent the value
        byte[] valueBytes = new byte[length]
        int bytesRead = bufferedStream.read(valueBytes)
        if (bytesRead != length) {
            throw new IOException("Failed to read value from header")
        }
        // Convert the bytes to a long (big-endian)
        ByteBuffer byteBuffer = ByteBuffer.wrap(valueBytes)
        return byteBuffer.order(ByteOrder.BIG_ENDIAN)
    }

    /**
     * Convert an int offset to a hex formatted offset for skiping to the offset in the buffered input stream.
     * @param offset in integer form
     * @return offset in hex formatted Long
     */
    private static Long hexOffset(int offset) {
        // Convert to hexadecimal string
        String hexString = "0x" + Integer.toHexString(offset)
        // return Long value of hex string
        return Long.decode(hexString)
    }
}
