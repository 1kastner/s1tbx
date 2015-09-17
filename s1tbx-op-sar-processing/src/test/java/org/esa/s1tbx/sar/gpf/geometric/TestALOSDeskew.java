package org.esa.s1tbx.sar.gpf.geometric;

import org.esa.s1tbx.commons.S1TBXTests;
import org.esa.s1tbx.commons.TestData;
import org.esa.snap.framework.datamodel.Product;
import org.esa.snap.framework.gpf.OperatorSpi;
import org.esa.snap.gpf.TestProcessor;
import org.esa.snap.util.TestUtils;
import org.junit.Ignore;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertNotNull;

/**
 * Created by lveci on 24/10/2014.
 */
public class TestALOSDeskew {

    static {
        TestUtils.initTestEnvironment();
    }
    private final static OperatorSpi spi = new ALOSDeskewingOp.Spi();
    private final static TestProcessor testProcessor = S1TBXTests.createS1TBXTestProcessor();

    private String[] exceptionExemptions = {"PALSAR products only"};

    /**
     * Processes a product and compares it to processed product known to be correct
     *
     * @throws Exception general exception
     */
    @Test
    @Ignore
    public void testProcessing() throws Exception {
        final File inputFile = TestData.inputALOS1_1;
        if (!inputFile.exists()) {
            TestUtils.skipTest(this, inputFile + " not found");
            return;
        }
        final Product sourceProduct = TestUtils.readSourceProduct(inputFile);

        final ALOSDeskewingOp op = (ALOSDeskewingOp) spi.createOperator();
        assertNotNull(op);
        op.setSourceProduct(sourceProduct);

        // get targetProduct: execute initialize()
        final Product targetProduct = op.getTargetProduct();
        TestUtils.verifyProduct(targetProduct, true, true, true);

        final float[] expected = new float[] { 178303.08f, 33205.94f, -130.6396f };
        TestUtils.comparePixels(targetProduct, targetProduct.getBandAt(0).getName(), 300, 400, expected);
    }

    @Test
    public void testProcessAllALOS() throws Exception {
        testProcessor.testProcessAllInPath(spi, S1TBXTests.rootPathsALOS, "ALOS PALSAR CEOS", null, exceptionExemptions);
    }
}
