/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2007-2015 Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.broad.igv.bbfile;

import htsjdk.samtools.seekablestream.SeekableStream;
import org.apache.log4j.Logger;
import org.broad.igv.util.CompressionUtils;
import org.broad.igv.util.LittleEndianInputStream;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Created by IntelliJ IDEA.
 * User: Owner
 * Date: May 5, 2010
 * Time: 8:27:56 PM
 * To change this template use File | Settings | File Templates.
 */
/*
*   Container class for reading and storing a block of zoom level data records.
* */
public class ZoomDataBlock {

    private static Logger log = Logger.getLogger(ZoomDataBlock.class);

    // Bed data block access variables   - for reading in bed records from a file
    private long fileOffset;       // data block file offset
    private long dataBlockSize;    // byte size for data block specified in the R+ leaf
    private boolean isLowToHigh;   // if true, data is low to high byte order; else high to low

    // defines the zoom level source chromosomes
    private int zoomLevel;         // zoom level for the R+ chromosome data location tree
    private HashMap<Integer, String> chromosomeMap;  // map of chromosome ID's and corresponding names
    private RPTreeLeafNodeItem leafHitItem;   //R+ leaf item with chromosome region and file data location

    // Provides uncompressed byte stream data reader
    private byte[] zoomBuffer;  // buffer containing leaf block data uncompressed
    private int remDataSize;   // number of unread decompressed data bytes

    // byte stream readers
    private LittleEndianInputStream lbdis = null;    // low to high byte stream reader
    private DataInputStream dis = null;       // high to low byte stream reader

    // Bed data extraction members
    private ArrayList<ZoomDataRecord> zoomDataList; // array of zoom level data

    /*
    *   Constructor for Bed data block reader.
    *
    *   Parameters:
    *       zoomLevel - zoom level for data block
    *       fis - file input stream handle
    *       leafItem - R+ tree leaf item containing block data file location
    *       chromIDTree - B+ chromosome index tree returns chromosome ID's for names
    *       isLowToHigh - byte order is low to high if true; else high to low
    *       uncompressBufSize - byte size for decompression buffer; else 0 for uncompressed
    * */

    public ZoomDataBlock(int zoomLevel, SeekableStream fis, RPTreeLeafNodeItem leafHitItem,
                         HashMap<Integer, String> chromosomeMap, boolean isLowToHigh, int uncompressBufSize) {

        this.zoomLevel = zoomLevel;
        this.leafHitItem = leafHitItem;
        this.chromosomeMap = chromosomeMap;
        this.isLowToHigh = isLowToHigh;

        fileOffset = this.leafHitItem.getDataOffset();
        dataBlockSize = this.leafHitItem.geDataSize();
        byte[] buffer = new byte[(int) dataBlockSize];

        // read Bed data block into a buffer
        try {
            fis.seek(fileOffset);
            fis.readFully(buffer);

            // decompress if necessary - the buffer size is 0 for uncomressed data
            // Note:  BBFile Table C specifies a decompression buffer size
            if (uncompressBufSize > 0)
                zoomBuffer = (new CompressionUtils()).decompress(buffer, uncompressBufSize);
            else
                zoomBuffer = buffer;    // use uncompressed read buffer directly

        } catch (IOException ex) {
            log.error("Error reading Zoom level " + this.zoomLevel + " data for leaf item ",  ex);
            String error = String.format("Error reading zoom level %d data for leaf item %d\n", this.zoomLevel);
            throw new RuntimeException(error, ex);
        }

        // wrap the bed buffer as an input stream
        if (this.isLowToHigh)
            lbdis = new LittleEndianInputStream(new ByteArrayInputStream(zoomBuffer));
        else
            dis = new DataInputStream(new ByteArrayInputStream(zoomBuffer));

        // initialize unread data size
        remDataSize = zoomBuffer.length;

        // use method getZoomData to extract block data
    }

    /*
    *   Method returns all zoom level data within the decompressed block buffer
    *
    *   Parameters:
    *       selectionRegion - chromosome region for selecting zoom level data records
    *       contained - indicates selected data must be contained in selection region
    *           if true, else may intersect selection region
    *
    *   Returns:
    *      zoom data records in the data block
    *
    *   Note: Remaining bytes to data block are used to determine end of reading
    *   since a zoom record count for the data block is not known.
    * */

    public ArrayList<ZoomDataRecord> getZoomData(RPChromosomeRegion selectionRegion,
                                                 boolean contained) {

        int chromID, chromStart, chromEnd, validCount;
        float minVal, maxVal, sumData, sumSquares;
        int itemHitValue;
        int recordNumber = 0;

        // allocate the bed feature array list
        zoomDataList = new ArrayList<ZoomDataRecord>();

        // check if all leaf items are selection hits
        RPChromosomeRegion itemRegion = new RPChromosomeRegion(leafHitItem.getChromosomeBounds());
        int leafHitValue = itemRegion.compareRegions(selectionRegion);

        try {
            //for(int index = 0; mRemDataSize >= ZoomDataRecord.RECORD_SIZE; ++index) {
            for (int index = 0; remDataSize > 0; ++index) {
                recordNumber = index + 1;

                if (isLowToHigh) {  // buffer stream arranged low to high bytes
                    chromID = lbdis.readInt();
                    chromStart = lbdis.readInt();
                    chromEnd = lbdis.readInt();
                    validCount = lbdis.readInt();
                    minVal = lbdis.readFloat();
                    maxVal = lbdis.readFloat();
                    sumData = lbdis.readFloat();
                    sumSquares = lbdis.readFloat();
                } else { // buffer stream arranged high to low bytes
                    chromID = dis.readInt();
                    chromStart = dis.readInt();
                    chromEnd = dis.readInt();
                    validCount = dis.readInt();
                    minVal = dis.readFloat();
                    maxVal = dis.readFloat();
                    sumData = dis.readFloat();
                    sumSquares = dis.readFloat();
                }

                if (leafHitValue == 0) {     // contained leaf region always a hit
                    String chromName = chromosomeMap.get(chromID);
                    ZoomDataRecord zoomRecord = new ZoomDataRecord(zoomLevel, recordNumber, chromName,
                            chromID, chromStart, chromEnd, validCount, minVal, maxVal, sumData, sumSquares);
                    zoomDataList.add(zoomRecord);
                } else {      // test for hit
                    itemRegion = new RPChromosomeRegion(chromID, chromStart, chromID, chromEnd);
                    itemHitValue = itemRegion.compareRegions(selectionRegion);

                    // itemHitValue < 2 for intersection; itemHitValue == 0 for is contained
                    if (!contained && Math.abs(itemHitValue) < 2 || itemHitValue == 0) {
                        String chromName = chromosomeMap.get(chromID);
                        ZoomDataRecord zoomRecord = new ZoomDataRecord(zoomLevel, recordNumber, chromName,
                                chromID, chromStart, chromEnd, validCount, minVal, maxVal, sumData, sumSquares);
                        zoomDataList.add(zoomRecord);
                    }
                }

                // compute data block remainder fom size item read
                remDataSize -= ZoomDataRecord.RECORD_SIZE;
            }

        } catch (IOException ex) {
            log.error("Read error for zoom level " + zoomLevel + " leaf item " );

            // accept this as an end of block condition unless no items were read
            if (recordNumber == 1)
                throw new RuntimeException("Read error for zoom level " + zoomLevel + " leaf item ");
        }

        return zoomDataList;
    }

    public void print() {
        log.debug("Zoom Level " + zoomLevel + "data for leaf item :");

        for (int index = 0; index <= zoomDataList.size(); ++index) {
            // zoom data records print themselves
            zoomDataList.get(index).print();
        }
    }

}
