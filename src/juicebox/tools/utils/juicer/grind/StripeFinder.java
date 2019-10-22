/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2011-2019 Broad Institute, Aiden Lab
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
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 */

package juicebox.tools.utils.juicer.grind;

import juicebox.data.*;
import juicebox.mapcolorui.Feature2DHandler;
import juicebox.tools.utils.common.MatrixTools;
import juicebox.tools.utils.common.UNIXTools;
import juicebox.tools.utils.dev.drink.ExtractingOEDataUtils;
import juicebox.track.feature.Feature2D;
import juicebox.track.feature.Feature2DList;
import juicebox.windowui.HiCZoom;
import juicebox.windowui.NormalizationType;
import org.apache.commons.math.linear.RealMatrix;
import org.broad.igv.feature.Chromosome;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class StripeFinder implements RegionFinder {

    private Integer x;
    private Integer y;
    private Integer z;
    private Dataset ds;
    private Feature2DList features;
    private String path;
    private ChromosomeHandler chromosomeHandler;
    private NormalizationType norm;
    private boolean useObservedOverExpected;
    private boolean useDenseLabels;
    private boolean onlyMakePositiveExamples;
    private boolean ignoreDirectionOrientation;
    private Set<Integer> resolutions;
    private int offsetOfCornerFromDiag;
    private int stride;
    private boolean useAmorphicLabeling;

    public StripeFinder(int x, int y, int z, Dataset ds, Feature2DList features, File outputDirectory, ChromosomeHandler chromosomeHandler, NormalizationType norm,
                        boolean useObservedOverExpected, boolean useDenseLabels, Set<Integer> resolutions,
                        int offsetOfCornerFromDiag, int stride, boolean onlyMakePositiveExamples, boolean ignoreDirectionOrientation,
                        boolean useAmorphicLabeling) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.ds = ds;
        this.features = features;
        this.path = outputDirectory.getPath();
        this.chromosomeHandler = chromosomeHandler;
        this.norm = norm;
        this.useObservedOverExpected = useObservedOverExpected;
        this.useDenseLabels = useDenseLabels;
        this.resolutions = resolutions;
        this.offsetOfCornerFromDiag = offsetOfCornerFromDiag;
        this.stride = stride;
        this.onlyMakePositiveExamples = onlyMakePositiveExamples;
        this.ignoreDirectionOrientation = ignoreDirectionOrientation;
        this.useAmorphicLabeling = useAmorphicLabeling;
    }

    public static void getTrainingDataAndSaveToFile(Dataset ds, NormalizationType norm, MatrixZoomData zd, Chromosome chrom, int rowIndex, int colIndex, int resolution,
                                                    Feature2DHandler feature2DHandler, Integer x, Integer y, String posPath, String negPath,
                                                    Writer posWriter, Writer posLabelWriter, Writer negWriter, boolean isVerticalStripe,
                                                    boolean useObservedOverExpected, boolean ignoreDirectionOrientation, boolean onlyMakePositiveExamples,
                                                    boolean useAmorphicLabeling) throws IOException {

        int rectULX = rowIndex;
        int rectULY = colIndex;
        int rectLRX = rowIndex + x;
        int rectLRY = colIndex + y;
        int numRows = x;
        int numCols = y;

        if (isVerticalStripe) {
            rectULX = rowIndex - y;
            rectULY = colIndex - x;
            rectLRX = rowIndex;
            rectLRY = colIndex;
            numRows = y;
            numCols = x;
        }

        RealMatrix localizedRegionData;
        if (useObservedOverExpected) {
            ExpectedValueFunction df = ds.getExpectedValues(zd.getZoom(), norm);
            if (df == null) {
                System.err.println("O/E data not available at " + zd.getZoom() + " " + norm);
                return;
            }
            localizedRegionData = ExtractingOEDataUtils.extractObsOverExpBoundedRegion(zd, rectULX, rectLRX,
                    rectULY, rectLRY, numRows, numCols, norm, true, df, chrom.getIndex(), 2, true, ExtractingOEDataUtils.ThresholdType.LOG_OE_BOUNDED);
        } else {
            localizedRegionData = HiCFileTools.extractLocalBoundedRegion(zd,
                    rectULX, rectLRX, rectULY, rectLRY, numRows, numCols, norm, true);
        }

        net.sf.jsi.Rectangle currentWindow = new net.sf.jsi.Rectangle(rectULX * resolution,
                rectULY * resolution, rectLRX * resolution, rectLRY * resolution);

        List<Feature2D> inputListFoundFeatures = feature2DHandler.getContainedFeatures(chrom.getIndex(), chrom.getIndex(),
                currentWindow);

        boolean stripeIsFound = false;

        int[][] labelsMatrix = new int[numRows][numCols];
        int[][] experimentalLabelsMatrix = new int[numRows][numCols];
        for (Feature2D feature2D : inputListFoundFeatures) {
            int rowLength = Math.max((feature2D.getEnd1() - feature2D.getStart1()) / resolution, 1);
            int colLength = Math.max((feature2D.getEnd2() - feature2D.getStart2()) / resolution, 1);

            if (ignoreDirectionOrientation || stripeIsCorrectOrientation(rowLength, colLength, isVerticalStripe)) {

                int startRowOf1 = feature2D.getStart1() / resolution - rectULX;
                int startColOf1 = feature2D.getStart2() / resolution - rectULY;
                MatrixTools.labelRegionWithOnes(labelsMatrix, rowLength, numRows, colLength, numCols, startRowOf1, startColOf1);

                if (useAmorphicLabeling) {
                    MatrixTools.labelEnrichedRegionWithOnes(experimentalLabelsMatrix, localizedRegionData.getData(), rowLength, numRows, colLength, numCols, startRowOf1, startColOf1);
                }
                stripeIsFound = true;
            }
        }

        double[][] finalData = localizedRegionData.getData();
        int[][] finalLabels = labelsMatrix;
        int[][] finalExpLabels = experimentalLabelsMatrix;
        String orientationType = "_Horzntl";

        if (isVerticalStripe) {
            finalData = appropriatelyTransformVerticalStripes(finalData);
            finalLabels = appropriatelyTransformVerticalStripes(finalLabels);
            finalExpLabels = appropriatelyTransformVerticalStripes(finalExpLabels);
            orientationType = "_Vertcl";
        }

        String filePrefix = chrom.getName() + "_" + rowIndex + "_" + colIndex + orientationType;

        if (stripeIsFound) {
            GrindUtils.saveGrindMatrixDataToFile(filePrefix + "_matrix", posPath, finalData, posWriter, false);
            GrindUtils.saveGrindMatrixDataToFile(filePrefix + "_matrix.label", posPath, finalLabels, posLabelWriter, false);
            if (useAmorphicLabeling) {
                GrindUtils.saveGrindMatrixDataToFile(filePrefix + "_matrix.label.exp", posPath, finalExpLabels, posLabelWriter, false);
            }
        } else if (!onlyMakePositiveExamples) {
            GrindUtils.saveGrindMatrixDataToFile(filePrefix + "_matrix", negPath, finalData, negWriter, false);
        }
    }

    @Override
    public void makeExamples() {

        ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

        final Feature2DHandler feature2DHandler = new Feature2DHandler(features);

        for (int resolution : resolutions) {
            for (Chromosome chrom : chromosomeHandler.getChromosomeArrayWithoutAllByAll()) {

                Runnable worker = new Runnable() {
                    @Override
                    public void run() {

                        int numFilesWrittenSoFar = 0;
                        int currentBatchNumber = 0;
                        int maxBatchSize = 10000;

                        String newFolderPath = path + "/" + resolution + "_chr" + chrom.getName();
                        UNIXTools.makeDir(newFolderPath);

                        BufferedWriter[] writers = new BufferedWriter[3];
                        try {


                            Matrix matrix = ds.getMatrix(chrom, chrom);
                            if (matrix == null) return;
                            HiCZoom zoom = ds.getZoomForBPResolution(resolution);
                            final MatrixZoomData zd = matrix.getZoomData(zoom);
                            if (zd == null) return;
                            System.out.println("Currently processing: " + chrom.getName());

                            generateWriters(currentBatchNumber, writers, newFolderPath);
                            String negPath = newFolderPath + "/negative_" + currentBatchNumber;
                            String posPath = newFolderPath + "/positive_" + currentBatchNumber;
                            UNIXTools.makeDir(negPath);
                            UNIXTools.makeDir(posPath);

                            // sliding along the diagonal
                            for (int rowIndex = 0; rowIndex < (chrom.getLength() / resolution) - y; rowIndex += stride) {
                                int startCol = Math.max(0, rowIndex - offsetOfCornerFromDiag);
                                int endCol = Math.min(rowIndex + offsetOfCornerFromDiag, (chrom.getLength() / resolution) - y);
                                for (int colIndex = startCol; colIndex < endCol; colIndex += stride) {
                                    getTrainingDataAndSaveToFile(ds, norm, zd, chrom, rowIndex, colIndex, resolution, feature2DHandler, x, y,
                                            posPath, negPath, writers[0], writers[2], writers[1], false,
                                            useObservedOverExpected, ignoreDirectionOrientation, onlyMakePositiveExamples, useAmorphicLabeling);
                                    numFilesWrittenSoFar++;
                                    if (numFilesWrittenSoFar > maxBatchSize) {
                                        numFilesWrittenSoFar = 0;
                                        currentBatchNumber++;

                                        generateWriters(currentBatchNumber, writers, newFolderPath);
                                        negPath = newFolderPath + "/negative_" + currentBatchNumber;
                                        posPath = newFolderPath + "/positive_" + currentBatchNumber;
                                        UNIXTools.makeDir(negPath);
                                        UNIXTools.makeDir(posPath);
                                    }
                                }
                            }
                            if (x != y) {
                                // only rectangular regions require the double traveling
                                for (int rowIndex = y; rowIndex < (chrom.getLength() / resolution); rowIndex += stride) {
                                    int startCol = Math.max(y, rowIndex - offsetOfCornerFromDiag);
                                    int endCol = Math.min(rowIndex + offsetOfCornerFromDiag, (chrom.getLength() / resolution));
                                    for (int colIndex = startCol; colIndex < endCol; colIndex += stride) {
                                        getTrainingDataAndSaveToFile(ds, norm, zd, chrom, rowIndex, colIndex, resolution, feature2DHandler, x, y,
                                                posPath, negPath, writers[0], writers[2], writers[1], true,
                                                useObservedOverExpected, ignoreDirectionOrientation, onlyMakePositiveExamples, useAmorphicLabeling);
                                        numFilesWrittenSoFar++;
                                        if (numFilesWrittenSoFar > maxBatchSize) {
                                            numFilesWrittenSoFar = 0;
                                            currentBatchNumber++;

                                            generateWriters(currentBatchNumber, writers, newFolderPath);
                                            negPath = newFolderPath + "/negative_" + currentBatchNumber;
                                            posPath = newFolderPath + "/positive_" + currentBatchNumber;
                                            UNIXTools.makeDir(negPath);
                                            UNIXTools.makeDir(posPath);
                                        }
                                    }
                                }
                            }

                            for (Writer writer : writers) {
                                writer.close();
                            }

                        } catch (Exception e) {
                            e.printStackTrace();
                            return;
                        }
                    }
                };
                executor.execute(worker);
            }

        }


        executor.shutdown();
        while (!executor.isTerminated()) {
        }
    }

    private static int[][] appropriatelyTransformVerticalStripes(int[][] data) {
        int[][] transformedData = new int[data[0].length][data.length];
        for (int i = 0; i < data.length; i++) {
            for (int j = 0; j < data[0].length; j++) {
                transformedData[data[0].length - j - 1][data.length - i - 1] = data[i][j];
            }
        }
        return transformedData;
    }

    private static double[][] appropriatelyTransformVerticalStripes(double[][] data) {
        double[][] transformedData = new double[data[0].length][data.length];
        for (int i = 0; i < data.length; i++) {
            for (int j = 0; j < data[0].length; j++) {
                transformedData[data[0].length - j - 1][data.length - i - 1] = data[i][j];
            }
        }
        return transformedData;
    }

    private static boolean stripeIsCorrectOrientation(int rowLength, int colLength, boolean isVerticalStripe) {
        if (isVerticalStripe) {
            return rowLength > colLength;
        } else {
            return colLength > rowLength;
        }
    }

    private void generateWriters(int currentBatchNumber, BufferedWriter[] writers, String newFolderPath) throws FileNotFoundException {
        writers[0] = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(
                newFolderPath + "/pos_file_names_" + currentBatchNumber + ".txt"), StandardCharsets.UTF_8));
        writers[1] = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(
                newFolderPath + "/neg_file_names_" + currentBatchNumber + ".txt"), StandardCharsets.UTF_8));
        writers[2] = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(
                newFolderPath + "/pos_label_file_names_" + currentBatchNumber + ".txt"), StandardCharsets.UTF_8));
    }
}