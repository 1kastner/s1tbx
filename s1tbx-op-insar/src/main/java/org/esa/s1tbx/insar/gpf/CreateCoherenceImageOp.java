package org.esa.s1tbx.insar.gpf;

import com.bc.ceres.core.ProgressMonitor;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProduct;
import org.esa.snap.core.gpf.annotations.TargetProduct;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.PosVector;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.eo.Constants;
import org.esa.snap.engine_utilities.eo.GeoUtils;
import org.esa.snap.engine_utilities.gpf.InputProductValidator;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.TileIndex;
import org.jblas.ComplexDouble;
import org.jblas.ComplexDoubleMatrix;
import org.jblas.DoubleMatrix;
import org.jblas.MatrixFunctions;
import org.jlinda.core.Orbit;
import org.jlinda.core.Point;
import org.jlinda.core.SLCImage;
import org.jlinda.core.utils.PolyUtils;
import org.jlinda.core.utils.SarUtils;
import org.jlinda.nest.utils.BandUtilsDoris;
import org.jlinda.nest.utils.CplxContainer;
import org.jlinda.nest.utils.ProductContainer;
import org.jlinda.nest.utils.TileUtilsDoris;

import javax.media.jai.BorderExtender;
import java.awt.*;
import java.util.HashMap;
import java.util.Map;

@OperatorMetadata(alias = "Coherence",
        category = "Radar/Interferometric/Products",
        authors = "Petar Marinkovic, Jun Lu",
        copyright = "Copyright (C) 2013 by PPO.labs",
        description = "Estimate coherence from stack of coregistered images")
public class CreateCoherenceImageOp extends Operator {

    @SourceProduct
    private Product sourceProduct;

    @TargetProduct
    private Product targetProduct;

    @Parameter(interval = "(1, 40]",
            description = "Size of coherence estimation window in Azimuth direction",
            defaultValue = "10",
            label = "Coherence Azimuth Window Size")
    private int cohWinAz = 10;

    @Parameter(interval = "(1, 40]",
            description = "Size of coherence estimation window in Range direction",
            defaultValue = "10",
            label = "Coherence Range Window Size")
    private int cohWinRg = 10;

    @Parameter(defaultValue="false", label="Subtract flat-earth phase in coherence phase")
    private boolean subtractFlatEarthPhase = false;

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

    @Parameter(description = "Use ground square pixel", defaultValue = "true", label = "Square Pixel")
    private Boolean squarePixel = true;

    // source
    private HashMap<Integer, CplxContainer> masterMap = new HashMap<>();
    private HashMap<Integer, CplxContainer> slaveMap = new HashMap<>();

    // target
    private HashMap<String, ProductContainer> targetMap = new HashMap<>();

    private boolean isTOPSARBurstProduct = false;
    private String productName = null;
    private String productTag = null;
    private Sentinel1Utils su = null;
    private Sentinel1Utils.SubSwathInfo[] subSwath = null;
    private int numSubSwaths = 0;
    private int subSwathIndex = 0;

    private double avgSceneHeight = 0.0;
    private MetadataElement mstRoot = null;
    private MetadataElement slvRoot = null;
    private org.jlinda.core.Point[] mstSceneCentreXYZ = null;
    private double slvSceneCentreAzimuthTime = 0.0;
    private HashMap<String, DoubleMatrix> flatEarthPolyMap = new HashMap<>();
    private int sourceImageWidth;
    private int sourceImageHeight;

    private static final int ORBIT_DEGREE = 3; // hardcoded
    private static final String COHERENCE_PHASE = "coherence_phase";

    /**
     * Initializes this operator and sets the one and only target product.
     * <p>The target product can be either defined by a field of type {@link Product} annotated with the
     * {@link TargetProduct TargetProduct} annotation or
     * by calling {@link #setTargetProduct} method.</p>
     * <p>The framework calls this method after it has created this operator.
     * Any client code that must be performed before computation of tile data
     * should be placed here.</p>
     *
     * @throws OperatorException
     *          If an error occurs during operator initialisation.
     * @see #getTargetProduct()
     */
    @Override
    public void initialize() throws OperatorException {

        try {
            productName = "coherence";
            productTag = "coh";

            mstRoot = AbstractMetadata.getAbstractedMetadata(sourceProduct);
            final MetadataElement slaveElem = sourceProduct.getMetadataRoot().getElement(AbstractMetadata.SLAVE_METADATA_ROOT);
            if(slaveElem != null) {
                slvRoot = slaveElem.getElements()[0];
            }

            checkUserInput();

            constructSourceMetadata();

            constructTargetMetadata();

            createTargetProduct();

            getSourceImageDimension();

            if (subtractFlatEarthPhase) {

                getMeanTerrainElevation();
                if (isTOPSARBurstProduct) {

                    getMstApproxSceneCentreXYZ();
                    getSlvApproxSceneCentreAzimuthTime();
                    constructFlatEarthPolynomialsForTOPSARProduct();
                } else {
                    constructFlatEarthPolynomials();
                }
            }

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
                su = new Sentinel1Utils(sourceProduct);
                subSwath = su.getSubSwath();
                numSubSwaths = su.getNumOfSubSwath();
                subSwathIndex = 1; // subSwathIndex is always 1 because of split product

                final String topsarTag = CreateInterferogramOp.getTOPSARTag(sourceProduct);
                productTag = productTag + "_" + topsarTag;
            }
        } catch (Exception e) {
            throw new OperatorException(e);
        }
    }

    private void constructSourceMetadata() throws Exception {

        // define sourceMaster/sourceSlave name tags
        final String masterTag = "mst";
        final String slaveTag = "slv";

        // get sourceMaster & sourceSlave MetadataElement

        /* organize metadata */
        // put sourceMaster metadata into the masterMap
        metaMapPut(masterTag, mstRoot, sourceProduct, masterMap);

        // plug sourceSlave metadata into slaveMap
        MetadataElement slaveElem = sourceProduct.getMetadataRoot().getElement(AbstractMetadata.SLAVE_METADATA_ROOT);
        if(slaveElem == null) {
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
                            final HashMap<Integer, CplxContainer> map) throws Exception {

        // TODO: include polarization flags/checks!
        // pull out band names for this product
        final String[] bandNames = product.getBandNames();
        final int numOfBands = bandNames.length;

        // map key: ORBIT NUMBER
        int mapKey = root.getAttributeInt(AbstractMetadata.ABS_ORBIT);

        // metadata: construct classes and define bands
        final String date = OperatorUtils.getAcquisitionDate(root);
        final SLCImage meta = new SLCImage(root);
        final Orbit orbit = new Orbit(root, ORBIT_DEGREE);
        Band bandReal = null;
        Band bandImag = null;

        // loop through all band names(!) : and pull out only one that matches criteria
        for (int i = 0; i < numOfBands; i++) {
            String bandName = bandNames[i];
            if (bandName.contains(tag) && bandName.contains(date)) {
                final Band band = product.getBandAt(i);
                if (BandUtilsDoris.isBandReal(band)) {
                    bandReal = band;
                } else if (BandUtilsDoris.isBandImag(band)) {
                    bandImag = band;
                }
            }
        }

        try {
            map.put(mapKey, new CplxContainer(date, meta, orbit, bandReal, bandImag));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void constructTargetMetadata() {

        // this means there is only one slave! but still do it in the loop
        // loop through masters
        for (Integer keyMaster : masterMap.keySet()) {

            CplxContainer master = masterMap.get(keyMaster);

            for (Integer keySlave : slaveMap.keySet()) {

                // generate name for product bands
                String productName = keyMaster.toString() + "_" + keySlave.toString();

                final CplxContainer slave = slaveMap.get(keySlave);
                final ProductContainer product = new ProductContainer(productName, master, slave, false);

                product.addBand(Unit.COHERENCE, productTag + "_" + master.date + "_" + slave.date);
                product.addBand(COHERENCE_PHASE, "Phase_" + productTag + "_" + master.date + "_" + slave.date);

                // put ifg-product bands into map
                targetMap.put(productName, product);
            }
        }
    }

    private void createTargetProduct() {

        targetProduct = new Product(productName,
                sourceProduct.getProductType(),
                sourceProduct.getSceneRasterWidth(),
                sourceProduct.getSceneRasterHeight());

        ProductUtils.copyProductNodes(sourceProduct, targetProduct);

        for (String key : targetMap.keySet()) {
            final String coherenceBandName = targetMap.get(key).getBandName(Unit.COHERENCE);
            final Band coherenceBand = targetProduct.addBand(coherenceBandName, ProductData.TYPE_FLOAT32);
            coherenceBand.setUnit(Unit.COHERENCE);

            if (subtractFlatEarthPhase) {
                final String coherencePhaseBandName = targetMap.get(key).getBandName(COHERENCE_PHASE);
                final Band coherencePhaseBand = targetProduct.addBand(coherencePhaseBandName, ProductData.TYPE_FLOAT32);
                coherencePhaseBand.setUnit(Unit.PHASE);
            }
        }
    }

    private void getSourceImageDimension() {
        sourceImageWidth = sourceProduct.getSceneRasterWidth();
        sourceImageHeight = sourceProduct.getSceneRasterHeight();
    }

    private void getMeanTerrainElevation() throws Exception {
        avgSceneHeight = AbstractMetadata.getAttributeDouble(mstRoot, AbstractMetadata.avg_scene_height);
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

    private void getSlvApproxSceneCentreAzimuthTime() throws Exception {

        final double firstLineTimeInDays = slvRoot.getAttributeUTC(AbstractMetadata.first_line_time).getMJD();
        final double firstLineTime = (firstLineTimeInDays - (int)firstLineTimeInDays) * Constants.secondsInDay;
        final double lastLineTimeInDays = slvRoot.getAttributeUTC(AbstractMetadata.last_line_time).getMJD();
        final double lastLineTime = (lastLineTimeInDays - (int)lastLineTimeInDays) * Constants.secondsInDay;

        slvSceneCentreAzimuthTime = 0.5*(firstLineTime + lastLineTime);
    }

    private void constructFlatEarthPolynomialsForTOPSARProduct() throws Exception {

        for (Integer keyMaster : masterMap.keySet()) {

            CplxContainer master = masterMap.get(keyMaster);

            for (Integer keySlave : slaveMap.keySet()) {

                CplxContainer slave = slaveMap.get(keySlave);

                for (int s = 0; s < numSubSwaths; s++) {

                    final int numBursts = subSwath[s].numOfBursts;

                    for (int b = 0; b < numBursts; b++) {

                        final String polynomialName = slave.name + "_" + s + "_" + b;

                        flatEarthPolyMap.put(polynomialName, CreateInterferogramOp.estimateFlatEarthPolynomial(
                                master, slave, s + 1, b, mstSceneCentreXYZ, orbitDegree, srpPolynomialDegree,
                                srpNumberPoints, avgSceneHeight, slvSceneCentreAzimuthTime, subSwath, su));
                    }
                }
            }
        }
    }

    private void constructFlatEarthPolynomials() throws Exception {

        for (Integer keyMaster : masterMap.keySet()) {

            CplxContainer master = masterMap.get(keyMaster);

            for (Integer keySlave : slaveMap.keySet()) {

                CplxContainer slave = slaveMap.get(keySlave);

                flatEarthPolyMap.put(slave.name, CreateInterferogramOp.estimateFlatEarthPolynomial(
                        master.metaData, master.orbit, slave.metaData, slave.orbit, sourceImageWidth,
                        sourceImageHeight, srpPolynomialDegree, srpNumberPoints, avgSceneHeight, sourceProduct));
            }
        }
    }


    /**
     * Called by the framework in order to compute a tile for the given target band.
     * <p>The default implementation throws a runtime exception with the message "not implemented".</p>
     *
     * @param targetTileMap   The target tiles associated with all target bands to be computed.
     * @param targetRectangle The rectangle of target tile.
     * @param pm              A progress monitor which should be used to determine computation cancelation requests.
     * @throws org.esa.snap.core.gpf.OperatorException
     *          If an error occurs during computation of the target raster.
     */
    @Override
    public void computeTileStack(Map<Band, Tile> targetTileMap, Rectangle targetRectangle, ProgressMonitor pm)
            throws OperatorException {

        if (isTOPSARBurstProduct) {
            computeTileForTOPSARProduct(targetTileMap, targetRectangle, pm);
        } else {
            computeTileForNormalProduct(targetTileMap, targetRectangle, pm);
        }
    }

    private void computeTileForNormalProduct(
            final Map<Band, Tile> targetTileMap, Rectangle targetRectangle, ProgressMonitor pm)
            throws OperatorException {

        try {
            final int cohx0 = targetRectangle.x - (cohWinRg - 1) / 2;
            final int cohy0 = targetRectangle.y - (cohWinAz - 1) / 2;
            final int cohw = targetRectangle.width + cohWinRg - 1;
            final int cohh = targetRectangle.height + cohWinAz - 1;
            final Rectangle extRect = new Rectangle(cohx0, cohy0, cohw, cohh);

            final BorderExtender border = BorderExtender.createInstance(BorderExtender.BORDER_ZERO);

            final int y0 = targetRectangle.y;
            final int yN = y0 + targetRectangle.height - 1;
            final int x0 = targetRectangle.x;
            final int xN = targetRectangle.x + targetRectangle.width - 1;

            for (String cohKey : targetMap.keySet()) {

                final ProductContainer product = targetMap.get(cohKey);

                final Tile tileRealMaster = getSourceTile(product.sourceMaster.realBand, extRect, border);
                final Tile tileImagMaster = getSourceTile(product.sourceMaster.imagBand, extRect, border);
                final ComplexDoubleMatrix dataMaster =
                        TileUtilsDoris.pullComplexDoubleMatrix(tileRealMaster, tileImagMaster);

                final Tile tileRealSlave = getSourceTile(product.sourceSlave.realBand, extRect, border);
                final Tile tileImagSlave = getSourceTile(product.sourceSlave.imagBand, extRect, border);
                final ComplexDoubleMatrix dataSlave =
                        TileUtilsDoris.pullComplexDoubleMatrix(tileRealSlave, tileImagSlave);

                for (int i = 0; i < dataMaster.length; i++) {
                    double tmp = norm(dataMaster.get(i));
                    dataMaster.put(i, dataMaster.get(i).mul(dataSlave.get(i).conj()));
                    dataSlave.put(i, new ComplexDouble(norm(dataSlave.get(i)), tmp));
                }

                ComplexDoubleMatrix cohMatrix = SarUtils.cplxCoherence(dataMaster, dataSlave, cohWinAz, cohWinRg);

                if (subtractFlatEarthPhase) {
                    DoubleMatrix rangeAxisNormalized = DoubleMatrix.linspace(x0, xN, targetRectangle.width);
                    rangeAxisNormalized = CreateInterferogramOp.normalizeDoubleMatrix(
                            rangeAxisNormalized, 0, sourceImageWidth - 1);

                    DoubleMatrix azimuthAxisNormalized = DoubleMatrix.linspace(y0, yN, targetRectangle.height);
                    azimuthAxisNormalized = CreateInterferogramOp.normalizeDoubleMatrix(
                            azimuthAxisNormalized, 0, sourceImageHeight - 1);

                    // pull polynomial from the map
                    final DoubleMatrix polyCoeffs = flatEarthPolyMap.get(product.sourceSlave.name);

                    // estimate the phase on the grid
                    final DoubleMatrix realReferencePhase =
                            PolyUtils.polyval(azimuthAxisNormalized, rangeAxisNormalized,
                                    polyCoeffs, PolyUtils.degreeFromCoefficients(polyCoeffs.length));

                    // compute the reference phase
                    final ComplexDoubleMatrix complexReferencePhase = new ComplexDoubleMatrix(
                            MatrixFunctions.cos(realReferencePhase),
                            MatrixFunctions.sin(realReferencePhase));

                    cohMatrix.muli(complexReferencePhase.conji());
                }

                saveComplexCoherence(cohMatrix, product, targetTileMap, targetRectangle);
            }

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        } finally {
            pm.done();
        }
    }

    private void computeTileForTOPSARProduct(
            final Map<Band, Tile> targetTileMap, Rectangle targetRectangle, ProgressMonitor pm)
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
                final int firstLineIdx = burstIndex*subSwath[subSwathIndex - 1].linesPerBurst;
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

                computePartialTile(subSwathIndex, burstIndex, targetTileMap, partialTileRectangle);
            }

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        } finally {
            pm.done();
        }
    }

    private void computePartialTile(final int subSwathIndex, final int burstIndex,
                                    final Map<Band, Tile> targetTileMap, final Rectangle targetRectangle) {

        try {
            final int cohx0 = targetRectangle.x - (cohWinRg - 1) / 2;
            final int cohy0 = targetRectangle.y - (cohWinAz - 1) / 2;
            final int cohw = targetRectangle.width + cohWinRg - 1;
            final int cohh = targetRectangle.height + cohWinAz - 1;
            final Rectangle rect = new Rectangle(cohx0, cohy0, cohw, cohh);

            final long minLine = burstIndex*subSwath[subSwathIndex - 1].linesPerBurst;
            final long maxLine = minLine + subSwath[subSwathIndex - 1].linesPerBurst - 1;
            final long minPixel = 0;
            final long maxPixel = subSwath[subSwathIndex - 1].samplesPerBurst - 1;

            final BorderExtender border = BorderExtender.createInstance(BorderExtender.BORDER_ZERO);

            final int y0 = targetRectangle.y;
            final int yN = y0 + targetRectangle.height - 1;
            final int x0 = targetRectangle.x;
            final int xN = x0 + targetRectangle.width - 1;

            for (String cohKey : targetMap.keySet()) {
                final ProductContainer product = targetMap.get(cohKey);

                Tile tileRealMaster = getSourceTile(product.sourceMaster.realBand, rect, border);
                Tile tileImagMaster = getSourceTile(product.sourceMaster.imagBand, rect, border);
                final ComplexDoubleMatrix dataMaster =
                        TileUtilsDoris.pullComplexDoubleMatrix(tileRealMaster, tileImagMaster);

                Tile tileRealSlave = getSourceTile(product.sourceSlave.realBand, rect, border);
                Tile tileImagSlave = getSourceTile(product.sourceSlave.imagBand, rect, border);
                final ComplexDoubleMatrix dataSlave =
                        TileUtilsDoris.pullComplexDoubleMatrix(tileRealSlave, tileImagSlave);

                for (int r = 0; r < dataMaster.rows; r++) {
                    final int y = cohy0 + r;
                    for (int c = 0; c < dataMaster.columns; c++) {
                        double tmp = norm(dataMaster.get(r, c));
                        if (y < minLine || y > maxLine) {
                            dataMaster.put(r, c, 0.0);
                        } else {
                            dataMaster.put(r, c, dataMaster.get(r, c).mul(dataSlave.get(r,c).conj()));
                        }
                        dataSlave.put(r, c, new ComplexDouble(norm(dataSlave.get(r, c)), tmp));
                    }
                }

                ComplexDoubleMatrix  cohMatrix = SarUtils.cplxCoherence(dataMaster, dataSlave, cohWinAz, cohWinRg);

                if (subtractFlatEarthPhase) {
                    DoubleMatrix rangeAxisNormalized = DoubleMatrix.linspace(x0, xN, targetRectangle.width);
                    rangeAxisNormalized = CreateInterferogramOp.normalizeDoubleMatrix(
                            rangeAxisNormalized, minPixel, maxPixel);

                    DoubleMatrix azimuthAxisNormalized = DoubleMatrix.linspace(y0, yN, targetRectangle.height);
                    azimuthAxisNormalized = CreateInterferogramOp.normalizeDoubleMatrix(
                            azimuthAxisNormalized, minLine, maxLine);

                    final String polynomialName = product.sourceSlave.name + "_" + (subSwathIndex - 1) + "_" + burstIndex;
                    final DoubleMatrix polyCoeffs = flatEarthPolyMap.get(polynomialName);

                    final DoubleMatrix realReferencePhase = PolyUtils.polyval(azimuthAxisNormalized, rangeAxisNormalized,
                            polyCoeffs, PolyUtils.degreeFromCoefficients(polyCoeffs.length));

                    final ComplexDoubleMatrix complexReferencePhase = new ComplexDoubleMatrix(
                            MatrixFunctions.cos(realReferencePhase),
                            MatrixFunctions.sin(realReferencePhase));

                    cohMatrix.muli(complexReferencePhase.conji());
                }

                saveComplexCoherence(cohMatrix, product, targetTileMap, targetRectangle);
            }

        } catch (Exception e) {
            throw new OperatorException(e);
        }
    }

    private void saveComplexCoherence(final ComplexDoubleMatrix  cohMatrix, final ProductContainer product,
                                      final Map<Band, Tile> targetTileMap, final Rectangle targetRectangle) {

        final int x0 = targetRectangle.x;
        final int y0 = targetRectangle.y;
        final int maxX = x0 + targetRectangle.width;
        final int maxY = y0 + targetRectangle.height;

        final Band coherenceBand = targetProduct.getBand(product.getBandName(Unit.COHERENCE));
        final Tile coherenceTile = targetTileMap.get(coherenceBand);
        final ProductData coherenceData = coherenceTile.getDataBuffer();

        ProductData coherencePhaseData = null;
        if (subtractFlatEarthPhase) {
            final Band coherencePhaseBand = targetProduct.getBand(product.getBandName(COHERENCE_PHASE));
            final Tile coherencePhaseTile = targetTileMap.get(coherencePhaseBand);
            coherencePhaseData = coherencePhaseTile.getDataBuffer();
        }

        final DoubleMatrix dataReal = cohMatrix.real();
        final DoubleMatrix dataImag = cohMatrix.imag();

        final double srcNoDataValue = product.sourceMaster.realBand.getNoDataValue();
        final Tile slvTileReal = getSourceTile(product.sourceSlave.realBand, targetRectangle);
        final ProductData srcSlvData = slvTileReal.getDataBuffer();
        final TileIndex srcSlvIndex = new TileIndex(slvTileReal);

        final TileIndex tgtIndex = new TileIndex(coherenceTile);
        for (int y = y0; y < maxY; y++) {
            tgtIndex.calculateStride(y);
            srcSlvIndex.calculateStride(y);
            final int yy = y - y0;
            for (int x = x0; x < maxX; x++) {
                final int tgtIdx = tgtIndex.getIndex(x);
                final int xx = x - x0;

                if (srcSlvData.getElemDoubleAt(srcSlvIndex.getIndex(x)) == srcNoDataValue) {
                    coherenceData.setElemFloatAt(tgtIdx, (float)srcNoDataValue);
                    if (subtractFlatEarthPhase) {
                        coherencePhaseData.setElemFloatAt(tgtIdx, (float)srcNoDataValue);
                    }
                } else {
                    final double cohI = dataReal.get(yy, xx);
                    final double cohQ = dataImag.get(yy, xx);
                    final double coh = Math.sqrt(cohI * cohI + cohQ * cohQ);
                    coherenceData.setElemFloatAt(tgtIdx, (float)coh);
                    if (subtractFlatEarthPhase) {
                        final double cohPhase = Math.atan2(cohQ, cohI);
                        coherencePhaseData.setElemFloatAt(tgtIdx, (float)cohPhase);
                    }
                }
            }
        }
    }

    private static double norm(final ComplexDouble number) {
        return number.real()*number.real() + number.imag()*number.imag();
    }

    private static double norm(final double real, final double imag) {
        return real*real + imag*imag;
    }

    public static DoubleMatrix coherence(final double[] iMst, final double[] qMst, final double[] iSlv, final double[] qSlv,
                                         final int winL, final int winP, int w, int h) {

        final ComplexDoubleMatrix input = new ComplexDoubleMatrix(h, w);
        final ComplexDoubleMatrix norms = new ComplexDoubleMatrix(h, w);
        for (int y = 0; y < h; y++) {
            final int stride = y * w;
            for (int x = 0; x < w; x++) {
                input.put(y, x, new ComplexDouble(iMst[stride + x],
                        qMst[stride + x]));
                norms.put(y, x, new ComplexDouble(iSlv[stride + x], qSlv[stride + x]));
            }
        }

        if (input.rows != norms.rows) {
            throw new IllegalArgumentException("coherence: not the same dimensions.");
        }

        // allocate output :: account for window overlap
        final int extent_RG = input.columns;
        final int extent_AZ = input.rows - winL + 1;
        final DoubleMatrix result = new DoubleMatrix(input.rows - winL + 1, input.columns - winP + 1);

        // temp variables
        int i, j, k, l;
        ComplexDouble sum;
        ComplexDouble power;
        final int leadingZeros = (winP - 1) / 2;  // number of pixels=0 floor...
        final int trailingZeros = (winP) / 2;     // floor...

        for (j = leadingZeros; j < extent_RG - trailingZeros; j++) {

            sum = new ComplexDouble(0);
            power = new ComplexDouble(0);

            //// Compute sum over first data block ////
            int minL = j - leadingZeros;
            int maxL = minL + winP;
            for (k = 0; k < winL; k++) {
                for (l = minL; l < maxL; l++) {
                    //sum.addi(input.get(k, l));
                    //power.addi(norms.get(k, l));
                    int inI = 2 * input.index(k, l);
                    sum.set(sum.real()+input.data[inI], sum.imag()+input.data[inI+1]);
                    power.set(power.real()+norms.data[inI], power.imag()+norms.data[inI+1]);
                }
            }
            result.put(0, minL, coherenceProduct(sum, power));

            //// Compute (relatively) sum over rest of data blocks ////
            final int maxI = extent_AZ - 1;
            for (i = 0; i < maxI; i++) {
                final int iwinL = i + winL;
                for (l = minL; l < maxL; l++) {
                    //sum.addi(input.get(iwinL, l).sub(input.get(i, l)));
                    //power.addi(norms.get(iwinL, l).sub(norms.get(i, l)));

                    int inI = 2 * input.index(i, l);
                    int inWinL = 2 * input.index(iwinL, l);
                    sum.set(sum.real()+(input.data[inWinL]-input.data[inI]), sum.imag()+(input.data[inWinL+1]-input.data[inI+1]));
                    power.set(power.real()+(norms.data[inWinL]-norms.data[inI]), power.imag()+(norms.data[inWinL+1]-norms.data[inI+1]));
                }
                result.put(i + 1, j - leadingZeros, coherenceProduct(sum, power));
            }
        }
        return result;
    }

    static double coherenceProduct(final ComplexDouble sum, final ComplexDouble power) {
        final double product = power.real() * power.imag();
//        return (product > 0.0) ? Math.sqrt(Math.pow(sum.abs(),2) / product) : 0.0;
        return (product > 0.0) ? sum.abs() / Math.sqrt(product) : 0.0;
    }

    public static void getDerivedParameters(Product srcProduct, DerivedParams param) throws Exception {

        final MetadataElement abs = AbstractMetadata.getAbstractedMetadata(srcProduct);
        double rangeSpacing = abs.getAttributeDouble(AbstractMetadata.range_spacing, 1);
        double azimuthSpacing = abs.getAttributeDouble(AbstractMetadata.azimuth_spacing, 1);
        final boolean srgrFlag = AbstractMetadata.getAttributeBoolean(abs, AbstractMetadata.srgr_flag);

        double groundRangeSpacing = rangeSpacing;
        if (rangeSpacing == AbstractMetadata.NO_METADATA) {
            azimuthSpacing = 1;
            groundRangeSpacing = 1;
        } else if (!srgrFlag) {
            final TiePointGrid incidenceAngle = OperatorUtils.getIncidenceAngle(srcProduct);
            if (incidenceAngle != null) {
                final int sourceImageWidth = srcProduct.getSceneRasterWidth();
                final int sourceImageHeight = srcProduct.getSceneRasterHeight();
                final int x = sourceImageWidth / 2;
                final int y = sourceImageHeight / 2;
                final double incidenceAngleAtCentreRangePixel = incidenceAngle.getPixelDouble(x, y);
                groundRangeSpacing /= Math.sin(incidenceAngleAtCentreRangePixel * Constants.DTOR);
            }
        }

        final double cohWinAz = param.cohWinRg * groundRangeSpacing / azimuthSpacing;
        if (cohWinAz < 1.0) {
            param.cohWinAz = 1;
            param.cohWinRg = (int) Math.round(azimuthSpacing / groundRangeSpacing);
        } else {
            param.cohWinAz = (int) Math.round(cohWinAz);
        }
    }

    public static class DerivedParams {
        public int cohWinAz = 0;
        public int cohWinRg = 0;
    }


    /**
     * The SPI is used to register this operator in the graph processing framework
     * via the SPI configuration file
     * {@code META-INF/services/org.esa.snap.core.gpf.OperatorSpi}.
     * This class may also serve as a factory for new operator instances.
     *
     * @see OperatorSpi#createOperator()
     * @see OperatorSpi#createOperator(java.util.Map, java.util.Map)
     */
    public static class Spi extends OperatorSpi {
        public Spi() {
            super(CreateCoherenceImageOp.class);
        }
    }
}
