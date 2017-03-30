/*
 * Copyright (C) 2015 by Array Systems Computing Inc. http://www.array.ca
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 */
package org.esa.s1tbx.io.ceos.alos;

import org.esa.s1tbx.io.binary.BinaryDBReader;
import org.esa.s1tbx.io.binary.BinaryFileReader;
import org.esa.s1tbx.io.binary.BinaryRecord;
import org.esa.s1tbx.io.ceos.CEOSImageFile;
import org.jdom2.Document;

import javax.imageio.stream.ImageInputStream;
import java.io.IOException;


public class AlosPalsarImageFile extends CEOSImageFile {

    private final static String mission = "alos";
    private final static String image_DefinitionFile = "image_file.xml";
    private final static String image_recordDefinition = "image_record.xml";
    private final static String processedData_recordDefinition = "processed_data_record.xml";
    private final String imageFileName;
    private final int productLevel;

    private final static Document imgDefXML = BinaryDBReader.loadDefinitionFile(mission, image_DefinitionFile);
    private final static Document imgRecordXML = BinaryDBReader.loadDefinitionFile(mission, image_recordDefinition);
    private final static Document procDataXML = BinaryDBReader.loadDefinitionFile(mission, processedData_recordDefinition);

    public AlosPalsarImageFile(final ImageInputStream imageStream, int prodLevel, String fileName)
            throws IOException {
        this.productLevel = prodLevel;
        imageFileName = fileName.toUpperCase();

        binaryReader = new BinaryFileReader(imageStream);
        imageFDR = new BinaryRecord(binaryReader, -1, imgDefXML, image_DefinitionFile);
        binaryReader.seek(imageFDR.getAbsolutPosition(imageFDR.getRecordLength()));
        imageRecords = new BinaryRecord[imageFDR.getAttributeInt("Number of lines per data set")];
        if(imageRecords.length > 0) {
            imageRecords[0] = createNewImageRecord(0);

            _imageRecordLength = imageRecords[0].getRecordLength();
            startPosImageRecords = imageRecords[0].getStartPos();
        }
        imageHeaderLength = imageFDR.getAttributeInt("Number of bytes of prefix data per record");
    }

    protected BinaryRecord createNewImageRecord(final int line) throws IOException {
        final long pos = imageFDR.getAbsolutPosition(imageFDR.getRecordLength()) + (line * _imageRecordLength);
        if (productLevel == AlosPalsarConstants.LEVEL1_5)
            return new BinaryRecord(binaryReader, pos, procDataXML, processedData_recordDefinition);
        else
            return new BinaryRecord(binaryReader, pos, imgRecordXML, image_recordDefinition);
    }

    public String getPolarization() {
        if (imageFileName.startsWith("IMG-") && imageFileName.length() > 6) {
            String pol = imageFileName.substring(4, 6);
            if (pol.equals("HH") || pol.equals("VV") || pol.equals("HV") || pol.equals("VH")) {
                return pol;
            } else if (imageRecords[0] != null) {
                try {
                    final int tx = imageRecords[0].getAttributeInt("Transmitted polarization");
                    final int rx = imageRecords[0].getAttributeInt("Received polarization");
                    if (tx == 1) pol = "V";
                    else pol = "H";

                    if (rx == 1) pol += "V";
                    else pol += "H";

                    return pol;
                } catch (Exception e) {
                    return "";
                }
            }
        }
        return "";
    }
}
