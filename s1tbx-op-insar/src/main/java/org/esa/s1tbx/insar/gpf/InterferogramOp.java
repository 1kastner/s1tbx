/*
 * Copyright (C) 2014 by Array Systems Computing Inc. http://www.array.ca
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
package org.esa.s1tbx.insar.gpf;

import com.bc.ceres.core.ProgressMonitor;
import org.apache.commons.math3.util.FastMath;
import org.esa.s1tbx.insar.gpf.support.Sentinel1Utils;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProduct;
import org.esa.snap.core.gpf.annotations.TargetProduct;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.PosVector;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.eo.Constants;
import org.esa.snap.engine_utilities.eo.GeoUtils;
import org.esa.snap.engine_utilities.gpf.InputProductValidator;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;
import org.esa.snap.engine_utilities.gpf.StackUtils;
import org.esa.snap.engine_utilities.gpf.TileIndex;
import org.jblas.ComplexDouble;
import org.jblas.ComplexDoubleMatrix;
import org.jblas.DoubleMatrix;
import org.jblas.MatrixFunctions;
import org.jblas.Solve;
import org.jlinda.core.Orbit;
import org.jlinda.core.Point;
import org.jlinda.core.SLCImage;
import org.jlinda.core.Window;
import org.jlinda.core.utils.MathUtils;
import org.jlinda.core.utils.PolyUtils;
import org.jlinda.core.utils.SarUtils;
import org.jlinda.nest.utils.BandUtilsDoris;
import org.jlinda.nest.utils.CplxContainer;
import org.jlinda.nest.utils.ProductContainer;
import org.jlinda.nest.utils.TileUtilsDoris;

import javax.media.jai.BorderExtender;
import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


@OperatorMetadata(alias = "Interferogram",
        category = "Radar/Interferometric/Products",
        authors = "Petar Marinkovic, Jun Lu",
        version = "1.0",
        description = "Compute interferograms from stack of coregistered S-1 images", internal = false)
public class InterferogramOp extends Operator {
    @SourceProduct
    private Product sourceProduct;

    @TargetProduct
    private Product targetProduct;

    @Parameter(defaultValue = "true", label = "Subtract flat-earth phase")
    private boolean subtractFlatEarthPhase = true;

    @Parameter(valueSet = {"1", "2", "3", "4", "5", "6", "7", "8"},
            description = "Order of 'Flat earth phase' polynomial",
            defaultValue = "5",
            label = "Degree of \"Flat Earth\" polynomial")
    private int srpPolynomialDegree = 5;

    @Parameter(valueSet = {"301", "401", "501", "601", "701", "801", "901", "1001"},
            description = "Number of points for the 'flat earth phase' polynomial estimation",
            defaultValue = "501",
            label = "Number of \"Flat Earth\" estimation points")
    private int srpNumberPoints = 501;

    @Parameter(valueSet = {"1", "2", "3", "4", "5"},
            description = "Degree of orbit (polynomial) interpolator",
            defaultValue = "3",
            label = "Orbit interpolation degree")
    private int orbitDegree = 3;

    @Parameter(defaultValue = "true", label = "Include coherence estimation")
    private boolean includeCoherence = true;

    @Parameter(description = "Size of coherence estimation window in Azimuth direction",
            defaultValue = "10",
            label = "Coherence Azimuth Window Size")
    private int cohWinAz = 10;

    @Parameter(description = "Size of coherence estimation window in Range direction",
            defaultValue = "10",
            label = "Coherence Range Window Size")
    private int cohWinRg = 10;

    @Parameter(description = "Use ground square pixel", defaultValue = "true", label = "Square Pixel")
    private Boolean squarePixel = true;

    // flat_earth_polynomial container
    private Map<String, DoubleMatrix> flatEarthPolyMap = new HashMap<>();
    private boolean flatEarthEstimated = false;

    // source
    private Map<String, CplxContainer> masterMap = new HashMap<>();
    private Map<String, CplxContainer> slaveMap = new HashMap<>();

    private String[] polarisations;
    private String[] subswaths = new String[]{""};

    // target
    private Map<String, ProductContainer> targetMap = new HashMap<>();

    // operator tags
    private String productTag = "ifg";
    private int sourceImageWidth;
    private int sourceImageHeight;

    private boolean isTOPSARBurstProduct = false;
    private Sentinel1Utils su = null;
    private Sentinel1Utils.SubSwathInfo[] subSwath = null;
    private int numSubSwaths = 0;
    private org.jlinda.core.Point[] mstSceneCentreXYZ = null;
    private int subSwathIndex = 0;
    private MetadataElement mstRoot = null;

    private static final boolean CREATE_VIRTUAL_BAND = true;
    private static final boolean OUTPUT_FLAT_EARTH_PHASE = false;
    private static final String PRODUCT_SUFFIX = "_Ifg";

    /**
     * Initializes this operator and sets the one and only target product.
     * <p>The target product can be either defined by a field of type {@link Product} annotated with the
     * {@link TargetProduct TargetProduct} annotation or
     * by calling {@link #setTargetProduct} method.</p>
     * <p>The framework calls this method after it has created this operator.
     * Any client code that must be performed before computation of tile data
     * should be placed here.</p>
     *
     * @throws org.esa.snap.core.gpf.OperatorException If an error occurs during operator initialisation.
     * @see #getTargetProduct()
     */
    @Override
    public void initialize() throws OperatorException {

        try {
            mstRoot = AbstractMetadata.getAbstractedMetadata(sourceProduct);

            checkUserInput();

            constructSourceMetadata();
            constructTargetMetadata();
            createTargetProduct();

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    private void checkUserInput() {

        try {
            final InputProductValidator validator = new InputProductValidator(sourceProduct);
            validator.checkIfSARProduct();
            validator.checkIfCoregisteredStack();
            validator.checkIfSLC();
            isTOPSARBurstProduct = !validator.isDebursted();

            if (isTOPSARBurstProduct) {
                final String mProcSysId = mstRoot.getAttributeString(AbstractMetadata.ProcessingSystemIdentifier);
                final float mVersion = Float.valueOf(mProcSysId.substring(mProcSysId.lastIndexOf(' ')));

                MetadataElement slaveElem = sourceProduct.getMetadataRoot().getElement(AbstractMetadata.SLAVE_METADATA_ROOT);
                if (slaveElem == null) {
                    slaveElem = sourceProduct.getMetadataRoot().getElement("Slave Metadata");
                }
                MetadataElement[] slaveRoot = slaveElem.getElements();
                for (MetadataElement slvRoot : slaveRoot) {
                    final String sProcSysId = slvRoot.getAttributeString(AbstractMetadata.ProcessingSystemIdentifier);
                    final float sVersion = Float.valueOf(sProcSysId.substring(sProcSysId.lastIndexOf(' ')));
                    if ((mVersion < 2.43 && sVersion >= 2.43 && mstRoot.getAttribute("EAP Correction") == null) ||
                            (sVersion < 2.43 && mVersion >= 2.43 && slvRoot.getAttribute("EAP Correction") == null)) {
                        throw new OperatorException("Source products cannot be InSAR pairs: one is EAP phase corrected" +
                                " and the other is not. Apply EAP Correction.");
                    }
                }

                su = new Sentinel1Utils(sourceProduct);
                subswaths = su.getSubSwathNames();
                subSwath = su.getSubSwath();
                numSubSwaths = su.getNumOfSubSwath();
                subSwathIndex = 1; // subSwathIndex is always 1 because of split product
            }

            polarisations = OperatorUtils.getPolarisations(sourceProduct);
            if (polarisations.length == 0) {
                polarisations = new String[]{""};
            }

            sourceImageWidth = sourceProduct.getSceneRasterWidth();
            sourceImageHeight = sourceProduct.getSceneRasterHeight();
        } catch (Exception e) {
            throw new OperatorException(e);
        }
    }

    private void getMstApproxSceneCentreXYZ() throws Exception {

        final int numOfBursts = subSwath[subSwathIndex - 1].numOfBursts;
        mstSceneCentreXYZ = new Point[numOfBursts];

        for (int b = 0; b < numOfBursts; b++) {
            final double firstLineTime = subSwath[subSwathIndex - 1].burstFirstLineTime[b];
            final double lastLineTime = subSwath[subSwathIndex - 1].burstLastLineTime[b];
            final double slrTimeToFirstPixel = subSwath[subSwathIndex - 1].slrTimeToFirstPixel;
            final double slrTimeToLastPixel = subSwath[subSwathIndex - 1].slrTimeToLastPixel;
            final double latUL = su.getLatitude(firstLineTime, slrTimeToFirstPixel, subSwathIndex);
            final double latUR = su.getLatitude(firstLineTime, slrTimeToLastPixel, subSwathIndex);
            final double latLL = su.getLatitude(lastLineTime, slrTimeToFirstPixel, subSwathIndex);
            final double latLR = su.getLatitude(lastLineTime, slrTimeToLastPixel, subSwathIndex);

            final double lonUL = su.getLongitude(firstLineTime, slrTimeToFirstPixel, subSwathIndex);
            final double lonUR = su.getLongitude(firstLineTime, slrTimeToLastPixel, subSwathIndex);
            final double lonLL = su.getLongitude(lastLineTime, slrTimeToFirstPixel, subSwathIndex);
            final double lonLR = su.getLongitude(lastLineTime, slrTimeToLastPixel, subSwathIndex);

            final double lat = (latUL + latUR + latLL + latLR) / 4.0;
            final double lon = (lonUL + lonUR + lonLL + lonLR) / 4.0;

            final PosVector mstSceneCenter = new PosVector();
            GeoUtils.geo2xyzWGS84(lat, lon, 0.0, mstSceneCenter);
            mstSceneCentreXYZ[b] = new Point(mstSceneCenter.toArray());
        }
    }

    private void constructFlatEarthPolynomials() throws Exception {

        for (String keyMaster : masterMap.keySet()) {

            CplxContainer master = masterMap.get(keyMaster);

            for (String keySlave : slaveMap.keySet()) {

                CplxContainer slave = slaveMap.get(keySlave);

                flatEarthPolyMap.put(slave.name, estimateFlatEarthPolynomial(
                        master.metaData, master.orbit, slave.metaData, slave.orbit, sourceImageWidth,
                        sourceImageHeight, srpPolynomialDegree, srpNumberPoints, sourceProduct));
            }
        }
    }

    private void constructFlatEarthPolynomialsForTOPSARProduct() throws Exception {

        for (String keyMaster : masterMap.keySet()) {

            CplxContainer master = masterMap.get(keyMaster);

            for (String keySlave : slaveMap.keySet()) {

                CplxContainer slave = slaveMap.get(keySlave);

                for (int s = 0; s < numSubSwaths; s++) {

                    final int numBursts = subSwath[s].numOfBursts;

                    for (int b = 0; b < numBursts; b++) {

                        final String polynomialName = slave.name + '_' + s + '_' + b;

                        flatEarthPolyMap.put(polynomialName, estimateFlatEarthPolynomial(
                                master, slave, s + 1, b, mstSceneCentreXYZ, orbitDegree, srpPolynomialDegree,
                                srpNumberPoints, subSwath, su));
                    }
                }
            }
        }
    }

    private void constructTargetMetadata() {

        for (String keyMaster : masterMap.keySet()) {

            CplxContainer master = masterMap.get(keyMaster);

            for (String keySlave : slaveMap.keySet()) {
                final CplxContainer slave = slaveMap.get(keySlave);

                if (master.polarisation == null || master.polarisation.equals(slave.polarisation)) {
                    // generate name for product bands
                    final String productName = keyMaster + '_' + keySlave;

                    final ProductContainer product = new ProductContainer(productName, master, slave, true);

                    // put ifg-product bands into map
                    targetMap.put(productName, product);
                }
            }
        }
    }

    private void constructSourceMetadata() throws Exception {

        // define sourceMaster/sourceSlave name tags
        final String masterTag = "mst";
        final String slaveTag = "slv";

        // get sourceMaster & sourceSlave MetadataElement
        final String slaveMetadataRoot = AbstractMetadata.SLAVE_METADATA_ROOT;

        // organize metadata
        // put sourceMaster metadata into the masterMap
        metaMapPut(masterTag, mstRoot, sourceProduct, masterMap);

        // put sourceSlave metadata into slaveMap
        MetadataElement slaveElem = sourceProduct.getMetadataRoot().getElement(slaveMetadataRoot);
        if (slaveElem == null) {
            slaveElem = sourceProduct.getMetadataRoot().getElement("Slave Metadata");
        }
        MetadataElement[] slaveRoot = slaveElem.getElements();
        for (MetadataElement meta : slaveRoot) {
            metaMapPut(slaveTag, meta, sourceProduct, slaveMap);
        }
    }

    private void metaMapPut(final String tag,
                            final MetadataElement root,
                            final Product product,
                            final Map<String, CplxContainer> map) throws Exception {

        for (String swath : subswaths) {
            final String subswath = swath.isEmpty() ? "" : '_' + swath.toUpperCase();

            for (String polarisation : polarisations) {
                final String pol = polarisation.isEmpty() ? "" : '_' + polarisation.toUpperCase();

                // map key: ORBIT NUMBER
                String mapKey = root.getAttributeInt(AbstractMetadata.ABS_ORBIT) + subswath + pol;

                // metadata: construct classes and define bands
                final String date = OperatorUtils.getAcquisitionDate(root);
                final SLCImage meta = new SLCImage(root, product);
                final Orbit orbit = new Orbit(root, orbitDegree);

                // TODO: resolve multilook factors
                meta.setMlAz(1);
                meta.setMlRg(1);

                Band bandReal = null;
                Band bandImag = null;
                for (String bandName : product.getBandNames()) {
                    if (bandName.contains(tag) && bandName.contains(date)) {
                        if (subswath.isEmpty() || bandName.contains(subswath)) {
                            if (pol.isEmpty() || bandName.contains(pol)) {
                                final Band band = product.getBand(bandName);
                                if (BandUtilsDoris.isBandReal(band)) {
                                    bandReal = band;
                                } else if (BandUtilsDoris.isBandImag(band)) {
                                    bandImag = band;
                                }
                            }
                        }
                    }
                }
                if(bandReal != null && bandImag != null) {
                    map.put(mapKey, new CplxContainer(date, meta, orbit, bandReal, bandImag));
                }
            }
        }
    }

    private void createTargetProduct() {

        // construct target product
        targetProduct = new Product(sourceProduct.getName() + PRODUCT_SUFFIX,
                                    sourceProduct.getProductType(),
                                    sourceProduct.getSceneRasterWidth(),
                                    sourceProduct.getSceneRasterHeight());

        ProductUtils.copyProductNodes(sourceProduct, targetProduct);

        for (String key : targetMap.keySet()) {
            final List<String> targetBandNames = new ArrayList<>();

            final ProductContainer container = targetMap.get(key);
            final CplxContainer master = container.sourceMaster;
            final CplxContainer slave = container.sourceSlave;

            final String subswath = master.subswath.isEmpty() ? "" : '_' + master.subswath.toUpperCase();
            final String pol = getPolarisationTag(master);
            final String tag = subswath + pol + '_' + master.date + '_' + slave.date;
            final String targetBandName_I = "i_" + productTag + tag;
            final Band iBand = targetProduct.addBand(targetBandName_I, ProductData.TYPE_FLOAT32);
            container.addBand(Unit.REAL, iBand.getName());
            iBand.setUnit(Unit.REAL);
            targetBandNames.add(iBand.getName());

            final String targetBandName_Q = "q_" + productTag + tag;
            final Band qBand = targetProduct.addBand(targetBandName_Q, ProductData.TYPE_FLOAT32);
            container.addBand(Unit.IMAGINARY, qBand.getName());
            qBand.setUnit(Unit.IMAGINARY);
            targetBandNames.add(qBand.getName());

            if (CREATE_VIRTUAL_BAND) {
                final String countStr = '_' + productTag + tag;
                ReaderUtils.createVirtualIntensityBand(targetProduct, targetProduct.getBand(targetBandName_I), targetProduct.getBand(targetBandName_Q), countStr);
                Band phaseBand = ReaderUtils.createVirtualPhaseBand(targetProduct, targetProduct.getBand(targetBandName_I), targetProduct.getBand(targetBandName_Q), countStr);

                targetProduct.setQuicklookBandName(phaseBand.getName());
                targetBandNames.add(phaseBand.getName());
            }

            if (includeCoherence) {
                final String targetBandCoh = "coh" + tag;
                final Band coherenceBand = targetProduct.addBand(targetBandCoh, ProductData.TYPE_FLOAT32);
                coherenceBand.setNoDataValueUsed(true);
                coherenceBand.setNoDataValue(master.realBand.getNoDataValue());
                container.addBand(Unit.COHERENCE, coherenceBand.getName());
                coherenceBand.setUnit(Unit.COHERENCE);
                targetBandNames.add(coherenceBand.getName());
            }

            if (subtractFlatEarthPhase && OUTPUT_FLAT_EARTH_PHASE) {
                final String targetBandFep = "fep" + tag;
                final Band fepBand = targetProduct.addBand(targetBandFep, ProductData.TYPE_FLOAT32);
                container.addBand(Unit.PHASE, fepBand.getName());
                fepBand.setUnit(Unit.PHASE);
                targetBandNames.add(fepBand.getName());
            }

            String slvProductName = StackUtils.findOriginalSlaveProductName(sourceProduct, container.sourceSlave.realBand);
            StackUtils.saveSlaveProductBandNames(targetProduct, slvProductName,
                                                 targetBandNames.toArray(new String[targetBandNames.size()]));
        }

        for(String bandName : sourceProduct.getBandNames()) {
            if(bandName.startsWith("elevation")) {
                ProductUtils.copyBand(bandName, sourceProduct, targetProduct, true);
            }
        }
     }

    public static String getPolarisationTag(final CplxContainer master) {
        return (master.polarisation == null || master.polarisation.isEmpty()) ? "" : '_' + master.polarisation.toUpperCase();
    }

    public static DoubleMatrix estimateFlatEarthPolynomial(
            final SLCImage masterMetadata, final Orbit masterOrbit, final SLCImage slaveMetadata,
            final Orbit slaveOrbit, final int sourceImageWidth, final int sourceImageHeight,
            final int srpPolynomialDegree, final int srpNumberPoints, final Product sourceProduct)
            throws Exception {

        long minLine = 0;
        long maxLine = sourceImageHeight;
        long minPixel = 0;
        long maxPixel = sourceImageWidth;

        int numberOfCoefficients = PolyUtils.numberOfCoefficients(srpPolynomialDegree);

        int[][] position = MathUtils.distributePoints(srpNumberPoints, new Window(minLine, maxLine, minPixel, maxPixel));

        // setup observation and design matrix
        DoubleMatrix y = new DoubleMatrix(srpNumberPoints);
        DoubleMatrix A = new DoubleMatrix(srpNumberPoints, numberOfCoefficients);

        double masterMinPi4divLam = (-4 * Math.PI * org.jlinda.core.Constants.SOL) / masterMetadata.getRadarWavelength();
        double slaveMinPi4divLam = (-4 * Math.PI * org.jlinda.core.Constants.SOL) / slaveMetadata.getRadarWavelength();
        final boolean isBiStaticStack = StackUtils.isBiStaticStack(sourceProduct);

        // Loop through vector or distributedPoints()
        for (int i = 0; i < srpNumberPoints; ++i) {

            double line = position[i][0];
            double pixel = position[i][1];

            // compute azimuth/range time for this pixel
            final double masterTimeRange = masterMetadata.pix2tr(pixel + 1);

            // compute xyz of this point : sourceMaster
            org.jlinda.core.Point xyzMaster = masterOrbit.lp2xyz(line + 1, pixel + 1, masterMetadata);
            org.jlinda.core.Point slaveTimeVector = slaveOrbit.xyz2t(xyzMaster, slaveMetadata);

            double slaveTimeRange;
            if (isBiStaticStack) {
                slaveTimeRange = 0.5 * (slaveTimeVector.x + masterTimeRange);
            } else {
                slaveTimeRange = slaveTimeVector.x;
            }

            // observation vector
            y.put(i, (masterMinPi4divLam * masterTimeRange) - (slaveMinPi4divLam * slaveTimeRange));

            // set up a system of equations
            // ______Order unknowns: A00 A10 A01 A20 A11 A02 A30 A21 A12 A03 for degree=3______
            double posL = PolyUtils.normalize2(line, minLine, maxLine);
            double posP = PolyUtils.normalize2(pixel, minPixel, maxPixel);

            int index = 0;

            for (int j = 0; j <= srpPolynomialDegree; j++) {
                for (int k = 0; k <= j; k++) {
                    A.put(i, index, (FastMath.pow(posL, (double) (j - k)) * FastMath.pow(posP, (double) k)));
                    index++;
                }
            }
        }

        // Fit polynomial through computed vector of phases
        DoubleMatrix Atranspose = A.transpose();
        DoubleMatrix N = Atranspose.mmul(A);
        DoubleMatrix rhs = Atranspose.mmul(y);

        return Solve.solve(N, rhs);
    }

    /**
     * Create a flat earth phase polynomial for a given burst in TOPSAR product.
     */
    public static DoubleMatrix estimateFlatEarthPolynomial(
            final CplxContainer master, final CplxContainer slave, final int subSwathIndex, final int burstIndex,
            final org.jlinda.core.Point[] mstSceneCentreXYZ, final int orbitDegree, final int srpPolynomialDegree,
            final int srpNumberPoints, final Sentinel1Utils.SubSwathInfo[] subSwath, final Sentinel1Utils su)
            throws Exception {

        final double[][] masterOSV = getAdjacentOrbitStateVectors(master, mstSceneCentreXYZ[burstIndex]);
        final double[][] slaveOSV = getAdjacentOrbitStateVectors(slave, mstSceneCentreXYZ[burstIndex]);
        final Orbit masterOrbit = new Orbit(masterOSV, orbitDegree);
        final Orbit slaveOrbit = new Orbit(slaveOSV, orbitDegree);

        long minLine = burstIndex * subSwath[subSwathIndex - 1].linesPerBurst;
        long maxLine = minLine + subSwath[subSwathIndex - 1].linesPerBurst - 1;
        long minPixel = 0;
        long maxPixel = subSwath[subSwathIndex - 1].samplesPerBurst - 1;

        int numberOfCoefficients = PolyUtils.numberOfCoefficients(srpPolynomialDegree);

        int[][] position = MathUtils.distributePoints(srpNumberPoints, new Window(minLine, maxLine, minPixel, maxPixel));

        // setup observation and design matrix
        DoubleMatrix y = new DoubleMatrix(srpNumberPoints);
        DoubleMatrix A = new DoubleMatrix(srpNumberPoints, numberOfCoefficients);

        double masterMinPi4divLam = (-4 * Constants.PI * Constants.lightSpeed) / master.metaData.getRadarWavelength();
        double slaveMinPi4divLam = (-4 * Constants.PI * Constants.lightSpeed) / slave.metaData.getRadarWavelength();

        // Loop through vector or distributedPoints()
        for (int i = 0; i < srpNumberPoints; ++i) {

            double line = position[i][0];
            double pixel = position[i][1];

            // compute azimuth/range time for this pixel
            final double mstRgTime = subSwath[subSwathIndex - 1].slrTimeToFirstPixel +
                    pixel * su.rangeSpacing / Constants.lightSpeed;

            final double mstAzTime = line2AzimuthTime(line, subSwathIndex, burstIndex, subSwath);

            // compute xyz of this point : sourceMaster
            org.jlinda.core.Point xyzMaster = masterOrbit.lph2xyz(
                    mstAzTime, mstRgTime, 0.0, mstSceneCentreXYZ[burstIndex]);

            org.jlinda.core.Point slaveTimeVector = slaveOrbit.xyz2t(xyzMaster, slave.metaData.getSceneCentreAzimuthTime());

            final double slaveTimeRange = slaveTimeVector.x;

            // observation vector
            y.put(i, (masterMinPi4divLam * mstRgTime) - (slaveMinPi4divLam * slaveTimeRange));

            // set up a system of equations
            // ______Order unknowns: A00 A10 A01 A20 A11 A02 A30 A21 A12 A03 for degree=3______
            double posL = PolyUtils.normalize2(line, minLine, maxLine);
            double posP = PolyUtils.normalize2(pixel, minPixel, maxPixel);

            int index = 0;

            for (int j = 0; j <= srpPolynomialDegree; j++) {
                for (int k = 0; k <= j; k++) {
                    A.put(i, index, (FastMath.pow(posL, (double) (j - k)) * FastMath.pow(posP, (double) k)));
                    index++;
                }
            }
        }

        // Fit polynomial through computed vector of phases
        DoubleMatrix Atranspose = A.transpose();
        DoubleMatrix N = Atranspose.mmul(A);
        DoubleMatrix rhs = Atranspose.mmul(y);

        return Solve.solve(N, rhs);
    }

    private static double[][] getAdjacentOrbitStateVectors(
            final CplxContainer container, final org.jlinda.core.Point sceneCentreXYZ) {

        try {
            double[] time = container.orbit.getTime();
            double[] dataX = container.orbit.getData_X();
            double[] dataY = container.orbit.getData_Y();
            double[] dataZ = container.orbit.getData_Z();

            final int numOfOSV = dataX.length;
            double minDistance = 0.0;
            int minIdx = 0;
            for (int i = 0; i < numOfOSV; i++) {
                final double dx = dataX[i] - sceneCentreXYZ.x;
                final double dy = dataY[i] - sceneCentreXYZ.y;
                final double dz = dataZ[i] - sceneCentreXYZ.z;
                final double distance = Math.sqrt(dx * dx + dy * dy + dz * dz) / 1000.0;
                if (i == 0) {
                    minDistance = distance;
                    minIdx = i;
                    continue;
                }

                if (distance < minDistance) {
                    minDistance = distance;
                    minIdx = i;
                }
            }

            int stIdx, edIdx;
            if (minIdx < 3) {
                stIdx = 0;
                edIdx = Math.min(7, numOfOSV - 1);
            } else if (minIdx > numOfOSV - 5) {
                stIdx = Math.max(numOfOSV - 8, 0);
                edIdx = numOfOSV - 1;
            } else {
                stIdx = minIdx - 3;
                edIdx = minIdx + 4;
            }

            final double[][] adjacentOSV = new double[edIdx - stIdx + 1][4];
            int k = 0;
            for (int i = stIdx; i <= edIdx; i++) {
                adjacentOSV[k][0] = time[i];
                adjacentOSV[k][1] = dataX[i];
                adjacentOSV[k][2] = dataY[i];
                adjacentOSV[k][3] = dataZ[i];
                k++;
            }

            return adjacentOSV;
        } catch (Throwable e) {
            SystemUtils.LOG.warning("Unable to getAdjacentOrbitStateVectors " + e.getMessage());
        }
        return null;
    }

    private static double line2AzimuthTime(final double line, final int subSwathIndex, final int burstIndex,
                                           final Sentinel1Utils.SubSwathInfo[] subSwath) {

        final double firstLineTimeInDays = subSwath[subSwathIndex - 1].burstFirstLineTime[burstIndex] /
                Constants.secondsInDay;

        final double firstLineTime = (firstLineTimeInDays - (int) firstLineTimeInDays) * Constants.secondsInDay;

        return firstLineTime + (line - burstIndex * subSwath[subSwathIndex - 1].linesPerBurst) *
                subSwath[subSwathIndex - 1].azimuthTimeInterval;
    }

    private synchronized void estimateFlatEarth() throws Exception {
        if(flatEarthEstimated)
            return;
        if (subtractFlatEarthPhase) {
            if (isTOPSARBurstProduct) {

                getMstApproxSceneCentreXYZ();
                constructFlatEarthPolynomialsForTOPSARProduct();
            } else {
                constructFlatEarthPolynomials();
            }
            flatEarthEstimated = true;
        }
    }

    /**
     * Called by the framework in order to compute a tile for the given target band.
     * <p>The default implementation throws a runtime exception with the message "not implemented".</p>
     *
     * @param targetTileMap   The target tiles associated with all target bands to be computed.
     * @param targetRectangle The rectangle of target tile.
     * @param pm              A progress monitor which should be used to determine computation cancelation requests.
     * @throws org.esa.snap.core.gpf.OperatorException If an error occurs during computation of the target raster.
     */
    @Override
    public void computeTileStack(Map<Band, Tile> targetTileMap, Rectangle targetRectangle, ProgressMonitor pm)
            throws OperatorException {
        try {
            if (subtractFlatEarthPhase && !flatEarthEstimated) {
                estimateFlatEarth();
            }

            if (isTOPSARBurstProduct) {
                computeTileStackForTOPSARProduct(targetTileMap, targetRectangle, pm);
            } else {
                computeTileStackForNormalProduct(targetTileMap, targetRectangle, pm);
            }
        } catch (Exception e) {
            OperatorUtils.catchOperatorException(this.getId(), e);
        }
    }

    private void computeTileStackForNormalProduct(
            final Map<Band, Tile> targetTileMap, Rectangle targetRectangle, final ProgressMonitor pm)
            throws OperatorException {

        try {
            final int cohx0 = targetRectangle.x - (cohWinRg - 1) / 2;
            final int cohy0 = targetRectangle.y - (cohWinAz - 1) / 2;
            final int cohw = targetRectangle.width + cohWinRg - 1;
            final int cohh = targetRectangle.height + cohWinAz - 1;
            final Rectangle rect = new Rectangle(cohx0, cohy0, cohw, cohh);

            final BorderExtender border = BorderExtender.createInstance(BorderExtender.BORDER_ZERO);

            final int y0 = targetRectangle.y;
            final int yN = y0 + targetRectangle.height - 1;
            final int x0 = targetRectangle.x;
            final int xN = targetRectangle.x + targetRectangle.width - 1;

            for (String ifgKey : targetMap.keySet()) {

                final ProductContainer product = targetMap.get(ifgKey);

                /// check out results from master ///
                final Tile mstTileReal = getSourceTile(product.sourceMaster.realBand, targetRectangle, border);
                final Tile mstTileImag = getSourceTile(product.sourceMaster.imagBand, targetRectangle, border);
                final ComplexDoubleMatrix dataMaster = TileUtilsDoris.pullComplexDoubleMatrix(mstTileReal, mstTileImag);

                /// check out results from slave ///
                final Tile slvTileReal = getSourceTile(product.sourceSlave.realBand, targetRectangle, border);
                final Tile slvTileImag = getSourceTile(product.sourceSlave.imagBand, targetRectangle, border);
                final ComplexDoubleMatrix dataSlave = TileUtilsDoris.pullComplexDoubleMatrix(slvTileReal, slvTileImag);

                final double srcNoDataValue = product.sourceMaster.realBand.getNoDataValue();

                ComplexDoubleMatrix cohMatrix = null;
                if (includeCoherence) {
                    final Tile mstTileReal2 = getSourceTile(product.sourceMaster.realBand, rect, border);
                    final Tile mstTileImag2 = getSourceTile(product.sourceMaster.imagBand, rect, border);
                    final Tile slvTileReal2 = getSourceTile(product.sourceSlave.realBand, rect, border);
                    final Tile slvTileImag2 = getSourceTile(product.sourceSlave.imagBand, rect, border);
                    final ComplexDoubleMatrix dataMaster2 =
                            TileUtilsDoris.pullComplexDoubleMatrix(mstTileReal2, mstTileImag2);

                    final ComplexDoubleMatrix dataSlave2 =
                            TileUtilsDoris.pullComplexDoubleMatrix(slvTileReal2, slvTileImag2);

                    for (int i = 0; i < dataMaster2.length; i++) {
                        double tmp = norm(dataMaster2.get(i));
                        dataMaster2.put(i, dataMaster2.get(i).mul(dataSlave2.get(i).conj()));
                        dataSlave2.put(i, new ComplexDouble(norm(dataSlave2.get(i)), tmp));
                    }

                    cohMatrix = SarUtils.cplxCoherence(dataMaster2, dataSlave2, cohWinAz, cohWinRg);
                }

                DoubleMatrix realReferencePhase = null;
                ComplexDoubleMatrix complexReferencePhase = null;
                if (subtractFlatEarthPhase) {
                    // normalize range and azimuth axis
                    DoubleMatrix rangeAxisNormalized = DoubleMatrix.linspace(x0, xN, dataMaster.columns);
                    rangeAxisNormalized = normalizeDoubleMatrix(rangeAxisNormalized, 0, sourceImageWidth - 1);

                    DoubleMatrix azimuthAxisNormalized = DoubleMatrix.linspace(y0, yN, dataMaster.rows);
                    azimuthAxisNormalized = normalizeDoubleMatrix(azimuthAxisNormalized, 0, sourceImageHeight - 1);

                    // pull polynomial from the map
                    final DoubleMatrix polyCoeffs = flatEarthPolyMap.get(product.sourceSlave.name);

                    // estimate the phase on the grid
                    realReferencePhase = PolyUtils.polyval(azimuthAxisNormalized, rangeAxisNormalized,
                                              polyCoeffs, PolyUtils.degreeFromCoefficients(polyCoeffs.length));

                    // compute the reference phase
                    complexReferencePhase = new ComplexDoubleMatrix(MatrixFunctions.cos(realReferencePhase),
                                                                    MatrixFunctions.sin(realReferencePhase));

                    dataSlave.muli(complexReferencePhase); // no conjugate here!
                }
                dataMaster.muli(dataSlave.conji());

                // coherence
                DoubleMatrix cohDataReal = null, cohDataImag = null;
                ProductData tgtCohData = null, tgtCohPhaseData = null;
                if (includeCoherence) {
                    if (subtractFlatEarthPhase) {
                        cohMatrix.muli(complexReferencePhase.conji());
                    }
                    cohDataReal = cohMatrix.real();
                    cohDataImag = cohMatrix.imag();

                    final Band tgtCohBand = targetProduct.getBand(product.getBandName(Unit.COHERENCE));
                    final Tile tgtCohTile = targetTileMap.get(tgtCohBand);
                    tgtCohData = tgtCohTile.getDataBuffer();
                }

                ProductData samplesFep = null;
                if (subtractFlatEarthPhase && OUTPUT_FLAT_EARTH_PHASE) {
                    final Band targetBandFep = targetProduct.getBand(product.getBandName(Unit.PHASE));
                    final Tile tileOutFep = targetTileMap.get(targetBandFep);
                    samplesFep = tileOutFep.getDataBuffer();
                }

                /// commit to target ///
                final Band targetBand_I = targetProduct.getBand(product.getBandName(Unit.REAL));
                final Tile tileOutReal = targetTileMap.get(targetBand_I);

                final Band targetBand_Q = targetProduct.getBand(product.getBandName(Unit.IMAGINARY));
                final Tile tileOutImag = targetTileMap.get(targetBand_Q);

                // push all
                final ProductData samplesReal = tileOutReal.getDataBuffer();
                final ProductData samplesImag = tileOutImag.getDataBuffer();
                final DoubleMatrix dataReal = dataMaster.real();
                final DoubleMatrix dataImag = dataMaster.imag();
                final TileIndex tgtIndex = new TileIndex(tileOutReal);

                final ProductData srcSlvData = slvTileReal.getDataBuffer();
                final TileIndex srcSlvIndex = new TileIndex(slvTileReal);

                for (int y = y0; y <= yN; y++) {
                    tgtIndex.calculateStride(y);
                    srcSlvIndex.calculateStride(y);
                    final int yy = y - y0;
                    for (int x = x0; x <= xN; x++) {
                        final int tgtIdx = tgtIndex.getIndex(x);
                        final int xx = x - x0;
                        if (srcSlvData.getElemDoubleAt(srcSlvIndex.getIndex(x)) == srcNoDataValue) {
                            samplesReal.setElemFloatAt(tgtIdx, (float) srcNoDataValue);
                            samplesImag.setElemFloatAt(tgtIdx, (float) srcNoDataValue);
                            if (includeCoherence) {
                                tgtCohData.setElemFloatAt(tgtIdx, (float) srcNoDataValue);
                            }
                            if (samplesFep != null) {
                                samplesFep.setElemFloatAt(tgtIdx, (float) srcNoDataValue);
                            }
                        } else {
                            samplesReal.setElemFloatAt(tgtIdx, (float) dataReal.get(yy, xx));
                            samplesImag.setElemFloatAt(tgtIdx, (float) dataImag.get(yy, xx));
                            if (includeCoherence) {
                                final double cohI = cohDataReal.get(yy, xx);
                                final double cohQ = cohDataImag.get(yy, xx);
                                final double coh = Math.sqrt(cohI * cohI + cohQ * cohQ);
                                tgtCohData.setElemFloatAt(tgtIdx, (float) coh);
                            }
                            if (samplesFep != null && realReferencePhase != null) {
                                samplesFep.setElemFloatAt(tgtIdx, (float) realReferencePhase.get(yy, xx));
                            }
                        }
                    }
                }
            }

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        } finally {
            pm.done();
        }
    }

    private static double norm(final ComplexDouble number) {
        return number.real() * number.real() + number.imag() * number.imag();
    }

    private void computeTileStackForTOPSARProduct(
            final Map<Band, Tile> targetTileMap, final Rectangle targetRectangle, final ProgressMonitor pm)
            throws OperatorException {

        try {
            final int tx0 = targetRectangle.x;
            final int ty0 = targetRectangle.y;
            final int tw = targetRectangle.width;
            final int th = targetRectangle.height;
            final int txMax = tx0 + tw;
            final int tyMax = ty0 + th;
            //System.out.println("tx0 = " + tx0 + ", ty0 = " + ty0 + ", tw = " + tw + ", th = " + th);

            for (int burstIndex = 0; burstIndex < subSwath[subSwathIndex - 1].numOfBursts; burstIndex++) {
                final int firstLineIdx = burstIndex * subSwath[subSwathIndex - 1].linesPerBurst;
                final int lastLineIdx = firstLineIdx + subSwath[subSwathIndex - 1].linesPerBurst - 1;

                if (tyMax <= firstLineIdx || ty0 > lastLineIdx) {
                    continue;
                }

                final int ntx0 = tx0;
                final int ntw = tw;
                final int nty0 = Math.max(ty0, firstLineIdx);
                final int ntyMax = Math.min(tyMax, lastLineIdx + 1);
                final int nth = ntyMax - nty0;
                final Rectangle partialTileRectangle = new Rectangle(ntx0, nty0, ntw, nth);
                //System.out.println("burst = " + burstIndex + ": ntx0 = " + ntx0 + ", nty0 = " + nty0 + ", ntw = " + ntw + ", nth = " + nth);

                computePartialTile(subSwathIndex, burstIndex, partialTileRectangle, targetTileMap);
            }

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        } finally {
            pm.done();
        }
    }

    private void computePartialTile(final int subSwathIndex, final int burstIndex, Rectangle targetRectangle,
                                    final Map<Band, Tile> targetTileMap) throws Exception {

        try {
            final int cohx0 = targetRectangle.x - (cohWinRg - 1) / 2;
            final int cohy0 = targetRectangle.y - (cohWinAz - 1) / 2;
            final int cohw = targetRectangle.width + cohWinRg - 1;
            final int cohh = targetRectangle.height + cohWinAz - 1;
            final Rectangle rect = new Rectangle(cohx0, cohy0, cohw, cohh);
            final BorderExtender border = BorderExtender.createInstance(BorderExtender.BORDER_ZERO);

            final int y0 = targetRectangle.y;
            final int yN = y0 + targetRectangle.height - 1;
            final int x0 = targetRectangle.x;
            final int xN = x0 + targetRectangle.width - 1;

            final long minLine = burstIndex * subSwath[subSwathIndex - 1].linesPerBurst;
            final long maxLine = minLine + subSwath[subSwathIndex - 1].linesPerBurst - 1;
            final long minPixel = 0;
            final long maxPixel = subSwath[subSwathIndex - 1].samplesPerBurst - 1;

            Band targetBand_I;
            Band targetBand_Q;

            for (String ifgKey : targetMap.keySet()) {

                final ProductContainer product = targetMap.get(ifgKey);

                /// check out results from source ///
                final Tile mstTileReal = getSourceTile(product.sourceMaster.realBand, targetRectangle);
                final Tile mstTileImag = getSourceTile(product.sourceMaster.imagBand, targetRectangle);
                final ComplexDoubleMatrix dataMaster = TileUtilsDoris.pullComplexDoubleMatrix(mstTileReal, mstTileImag);

                /// check out results from source ///
                final Tile slvTileReal = getSourceTile(product.sourceSlave.realBand, targetRectangle);
                final Tile slvTileImag = getSourceTile(product.sourceSlave.imagBand, targetRectangle);
                final ComplexDoubleMatrix dataSlave = TileUtilsDoris.pullComplexDoubleMatrix(slvTileReal, slvTileImag);

                final double srcNoDataValue = product.sourceMaster.realBand.getNoDataValue();

                ComplexDoubleMatrix cohMatrix = null;
                if (includeCoherence) {
                    final Tile mstTileReal2 = getSourceTile(product.sourceMaster.realBand, rect, border);
                    final Tile mstTileImag2 = getSourceTile(product.sourceMaster.imagBand, rect, border);
                    final Tile slvTileReal2 = getSourceTile(product.sourceSlave.realBand, rect, border);
                    final Tile slvTileImag2 = getSourceTile(product.sourceSlave.imagBand, rect, border);

                    final ComplexDoubleMatrix dataMaster2 =
                            TileUtilsDoris.pullComplexDoubleMatrix(mstTileReal2, mstTileImag2);

                    final ComplexDoubleMatrix dataSlave2 =
                            TileUtilsDoris.pullComplexDoubleMatrix(slvTileReal2, slvTileImag2);

                    for (int r = 0; r < dataMaster2.rows; r++) {
                        final int y = cohy0 + r;
                        for (int c = 0; c < dataMaster2.columns; c++) {
                            double tmp = norm(dataMaster2.get(r, c));
                            if (y < minLine || y > maxLine) {
                                dataMaster2.put(r, c, 0.0);
                            } else {
                                dataMaster2.put(r, c, dataMaster2.get(r, c).mul(dataSlave2.get(r, c).conj()));
                            }
                            dataSlave2.put(r, c, new ComplexDouble(norm(dataSlave2.get(r, c)), tmp));
                        }
                    }

                    cohMatrix = SarUtils.cplxCoherence(dataMaster2, dataSlave2, cohWinAz, cohWinRg);
                }

                DoubleMatrix realReferencePhase = null;
                ComplexDoubleMatrix complexReferencePhase = null;
                if (subtractFlatEarthPhase) {
                    DoubleMatrix rangeAxisNormalized = DoubleMatrix.linspace(x0, xN, dataMaster.columns);
                    rangeAxisNormalized = normalizeDoubleMatrix(rangeAxisNormalized, minPixel, maxPixel);

                    DoubleMatrix azimuthAxisNormalized = DoubleMatrix.linspace(y0, yN, dataMaster.rows);
                    azimuthAxisNormalized = normalizeDoubleMatrix(azimuthAxisNormalized, minLine, maxLine);

                    final String polynomialName = product.sourceSlave.name + '_' + (subSwathIndex - 1) + '_' + burstIndex;
                    final DoubleMatrix polyCoeffs = flatEarthPolyMap.get(polynomialName);

                    realReferencePhase = PolyUtils.polyval(azimuthAxisNormalized, rangeAxisNormalized,
                                                           polyCoeffs, PolyUtils.degreeFromCoefficients(polyCoeffs.length));

                    complexReferencePhase = new ComplexDoubleMatrix(MatrixFunctions.cos(realReferencePhase),
                                                                    MatrixFunctions.sin(realReferencePhase));

                    dataSlave.muli(complexReferencePhase); // no conjugate here!
                }

                dataMaster.muli(dataSlave.conji());

                /// commit to target ///
                targetBand_I = targetProduct.getBand(product.getBandName(Unit.REAL));
                Tile tileOutReal = targetTileMap.get(targetBand_I);

                targetBand_Q = targetProduct.getBand(product.getBandName(Unit.IMAGINARY));
                Tile tileOutImag = targetTileMap.get(targetBand_Q);

                // coherence
                DoubleMatrix cohDataReal = null, cohDataImag = null;
                ProductData tgtCohData = null, tgtCohPhaseData = null;
                if (includeCoherence) {
                    if (subtractFlatEarthPhase) {
                        cohMatrix.muli(complexReferencePhase.conji());
                    }

                    cohDataReal = cohMatrix.real();
                    cohDataImag = cohMatrix.imag();

                    final Band tgtCohBand = targetProduct.getBand(product.getBandName(Unit.COHERENCE));
                    final Tile tgtCohTile = targetTileMap.get(tgtCohBand);
                    tgtCohData = tgtCohTile.getDataBuffer();
                }

                ProductData samplesFep = null;
                if (subtractFlatEarthPhase && OUTPUT_FLAT_EARTH_PHASE) {
                    final Band targetBandFep = targetProduct.getBand(product.getBandName(Unit.PHASE));
                    final Tile tileOutFep = targetTileMap.get(targetBandFep);
                    samplesFep = tileOutFep.getDataBuffer();
                }

                // push all
                final ProductData samplesReal = tileOutReal.getDataBuffer();
                final ProductData samplesImag = tileOutImag.getDataBuffer();
                final ProductData srcSlvData = slvTileReal.getDataBuffer();
                final DoubleMatrix dataReal = dataMaster.real();
                final DoubleMatrix dataImag = dataMaster.imag();

                final TileIndex tgtIndex = new TileIndex(tileOutReal);
                final TileIndex srcSlvIndex = new TileIndex(slvTileReal);

                for (int y = y0; y <= yN; y++) {
                    tgtIndex.calculateStride(y);
                    srcSlvIndex.calculateStride(y);
                    final int yy = y - y0;
                    for (int x = x0; x <= xN; x++) {
                        final int tgtIdx = tgtIndex.getIndex(x);
                        final int xx = x - x0;

                        if (srcSlvData.getElemDoubleAt(srcSlvIndex.getIndex(x)) == srcNoDataValue) {
                            samplesReal.setElemFloatAt(tgtIdx, (float) srcNoDataValue);
                            samplesImag.setElemFloatAt(tgtIdx, (float) srcNoDataValue);
                            if (includeCoherence) {
                                tgtCohData.setElemFloatAt(tgtIdx, (float) srcNoDataValue);
                            }
                            if (samplesFep != null) {
                                samplesFep.setElemFloatAt(tgtIdx, (float) srcNoDataValue);
                            }
                        } else {
                            samplesReal.setElemFloatAt(tgtIdx, (float) dataReal.get(yy, xx));
                            samplesImag.setElemFloatAt(tgtIdx, (float) dataImag.get(yy, xx));
                            if (includeCoherence) {
                                final double cohI = cohDataReal.get(yy, xx);
                                final double cohQ = cohDataImag.get(yy, xx);
                                final double coh = Math.sqrt(cohI * cohI + cohQ * cohQ);
                                tgtCohData.setElemFloatAt(tgtIdx, (float) coh);
                            }
                            if (samplesFep != null && realReferencePhase != null) {
                                samplesFep.setElemFloatAt(tgtIdx, (float) realReferencePhase.get(yy, xx));
                            }
                        }
                    }
                }
            }

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    public static DoubleMatrix normalizeDoubleMatrix(DoubleMatrix matrix, final double min, final double max) {
        matrix.subi(0.5 * (min + max));
        matrix.divi(0.25 * (max - min));
        return matrix;
    }

    /**
     * The SPI is used to register this operator in the graph processing framework
     * via the SPI configuration file
     * {@code META-INF/services/org.esa.snap.core.gpf.OperatorSpi}.
     * This class may also serve as a factory for new operator instances.
     *
     * @see org.esa.snap.core.gpf.OperatorSpi#createOperator()
     * @see org.esa.snap.core.gpf.OperatorSpi#createOperator(java.util.Map, java.util.Map)
     */
    public static class Spi extends OperatorSpi {

        public Spi() {
            super(InterferogramOp.class);
        }
    }

}
