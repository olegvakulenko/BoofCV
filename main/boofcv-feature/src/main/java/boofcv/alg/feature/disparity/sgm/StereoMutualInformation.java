/*
 * Copyright (c) 2011-2019, Peter Abeles. All Rights Reserved.
 *
 * This file is part of BoofCV (http://boofcv.org).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package boofcv.alg.feature.disparity.sgm;

import boofcv.alg.InputSanityCheck;
import boofcv.alg.filter.convolve.ConvolveImageNormalized;
import boofcv.alg.misc.GImageMiscOps;
import boofcv.alg.misc.ImageMiscOps;
import boofcv.alg.misc.ImageStatistics;
import boofcv.alg.misc.PixelMath;
import boofcv.factory.filter.kernel.FactoryKernelGaussian;
import boofcv.struct.convolve.Kernel1D_F32;
import boofcv.struct.image.GrayF32;
import boofcv.struct.image.GrayS32;
import boofcv.struct.image.GrayU16;
import boofcv.struct.image.GrayU8;
import org.ejml.UtilEjml;

import java.util.Random;

/**
 * <p></p>Computes the Mutual Information error metric from a rectified stereo pair. Mutual information
 * between two images is defined as: MI(I1,I2) = H<sub>I1</sub> + H<sub>I2</sub> + H<sub>I1,I2</sub>.
 * Where H is an entropy function, e.g. H<sub>I</sub> = -sum_i P<sub>I</sub>(i)log(P<sub>I</sub>(i)),
 * where P<sub>I</sub>(i) is the probability of a pixel in image 'I' having that intensity. See [1]
 * for details.</p>
 *
 * <p>This implementation has been designed to handle images with pixel intensity greater than 8-bit.
 * That was done by allowing the number of bins in a histogram to be specified.
 * High dynamic range images are problematic because the joint entropy instead of being a 256x256 image might now be a
 * 4096x4096 image. The PDF for each image might also appear to be too flat.</p>
 *
 * <p>[1] Hirschmuller, Heiko. "Stereo processing by semiglobal matching and mutual information."
 * IEEE Transactions on pattern analysis and machine intelligence 30.2 (2007): 328-341.</p>
 *
 * @author Peter Abeles
 */
public class StereoMutualInformation {

	// Kernel that's used to apply smoothing to 1D and 2D
	Kernel1D_F32 smoothKernel;
	GrayF32 smoothWork = new GrayF32(1,1); // work space for smoothing

	// To avoid log(0) this is added to all probabilities
	float eps = UtilEjml.F_EPS;

	// Maximum intensity value a pixel of the input image can have
	int maxPixelValue;
	// Storage for pixel intensity histogram after scaling
	int[] histogramIntensity;
	// The maximum intensity value after scaling is applied. Always histogram.length-1
	int maxHistogramValue;

	// total number of corresponding pixels with disparity values
	int totalDispPixels;

	// histogram mapping pixel intensities from left to right image
	GrayS32 histJoint = new GrayS32(1,1);

	// Storage for entropy. Initially the probabilities will be stored here but that is overwritten
	GrayF32 entropyJoint = new GrayF32(1,1);
	GrayF32 entropyLeft = new GrayF32(1,1);
	GrayF32 entropyRight = new GrayF32(1,1);

	// Precomputed scaled cost
	GrayU16 scaledCost = new GrayU16(1,1);

	public StereoMutualInformation() {
		// this is a reasonable default for 8-bit images
		configureHistogram(255,255);

		configureSmoothing(1);
	}

	/**
	 * Configures the histogram and how the input is scaled. For an 8-bit input image just pass in
	 * 0xFF for both values.
	 *
	 * @param maxPixelValue The maximum value a pixel in the input image can have
	 * @param maxHistogramValue The maximum value that the pixel can have after being scaled
	 */
	public void configureHistogram(int maxPixelValue , int maxHistogramValue ) {
		if( maxHistogramValue > maxPixelValue )
			throw new IllegalArgumentException("Maximum histogram value can't be more than max pixel value");

		this.maxPixelValue = maxPixelValue;
		this.maxHistogramValue = maxHistogramValue;
		int intensityBins = maxHistogramValue+1;
		histogramIntensity = new int[intensityBins];

		histJoint.reshape(intensityBins,intensityBins);
		entropyJoint.reshape(histJoint);
		entropyLeft.reshape(intensityBins,1);
		entropyRight.reshape(intensityBins,1);
		scaledCost.reshape(histJoint);
	}

	/**
	 * Computes random values for the cost between left and right values.
	 *
	 * @param rand Random number generator
	 */
	public void randomHistogram( Random rand , int maxCost ) {
		int N = scaledCost.totalPixels();
		for (int i = 0; i < N; i++) {
			scaledCost.data[i] = (short)rand.nextInt(maxCost);
		}
	}

	/**
	 * Amount of smooth that's applied to the kernels
	 * @param radius A radius of 3 is recommended in the paper
	 */
	public void configureSmoothing( int radius ) {
		smoothKernel = FactoryKernelGaussian.gaussian(1,true,32,-1,radius);
	}

	/**
	 * Process the images and compute the entropy terms which will be in turn used to compute mutual information
	 * @param left Left rectified image
	 * @param right Right rectified image
	 * @param minDisparity The minimum allowed disparity
	 * @param disparity Disparity from left to right
	 * @param invalid Value of disparity pixels which are invalid
	 */
	public void process(GrayU8 left , GrayU8 right , int minDisparity , GrayU8 disparity , int invalid ) {
		// Check input to make sure it's valid
		InputSanityCheck.checkSameShape(left,right);
		if( left.isSubimage() || right.isSubimage() || disparity.isSubimage() )
			throw new IllegalArgumentException("Can't process sub images. Is this a major issue? Could be fixed");
		disparity.reshape(left);

		// Compute entropy tables
		computeJointHistogram(left, right, minDisparity, disparity, invalid);
		computeProbabilities();
		computeEntropy();
	}

	/**
	 * Computes the mutual information cost given pixel values from left and right images. Must call
	 * {@link #process} first.
	 *
	 * @param leftValue Value in left image. I(x,y)
	 * @param rightValue Value of pixel in right image I(x-d,y)
	 * @return the mutual information score
	 */
	public float cost(int leftValue , int rightValue ) {
		// Scale the input value to be in the same range as the histogram
		int leftScale = scalePixelValue(leftValue);
		int rightScale = scalePixelValue(rightValue);

		// Equation 8b and 9a
		return -(entropyLeft.data[leftScale] + entropyRight.data[rightScale] - entropyJoint.unsafe_get(rightScale,leftScale));
	}

	public int costScaled( int leftValue , int rightValue ) {
		return scaledCost.unsafe_get(rightValue,leftValue);
	}

	/**
	 * Computes the joint histogram of pixel intensities (2D histogram) while skipping over pixels with
	 * no correspondences
	 */
	void computeJointHistogram(GrayU8 left, GrayU8 right, int minDisparity , GrayU8 disparity, int invalid) {
		// zero the histogram
		ImageMiscOps.fill(histJoint,0);

		int histLength = histogramIntensity.length;

		// Compute the joint histogram
		for (int row = 0; row < left.height; row++) {
			int idx = row*left.stride;
			for (int col = 0; col < left.width; col++, idx++ ) {
				int d = disparity.data[idx]&0xFF;
				// Don't consider pixels without correspondences
				if( d == invalid )
					continue;

				d += minDisparity;

				// NOTE: Paper says to take care to avoid double mappings due to occlusions. Not sure I'm doing that.

				// The equation below assumes that all disparitys are valid and won't result in a pixel going
				// outside the image
				int leftValue  =  left.data[idx  ]&0xFF; // I(x  ,y)
				int rightValue = right.data[idx-d]&0xFF; // I(x-d,y)

				// scale the pixel intensity for the histogram
				leftValue  = scalePixelValue(leftValue);
				rightValue = scalePixelValue(rightValue);

				// increment the histogram
				histJoint.data[leftValue*histLength+rightValue]++; // H(L,R) += 1
			}
		}
	}

	/**
	 * Computes the joint and image specific probabilities using the joint histogram.
	 */
	void computeProbabilities() {
		// Convert joint histogram into a joint probability
		float totalPixels = totalDispPixels = ImageStatistics.sum(histJoint);
		int histN = histJoint.totalPixels();
		for (int i = 0; i < histN; i++) {
			entropyJoint.data[i] = histJoint.data[i]/totalPixels;
		}

		// Compute probabilities for left and right images by summing rows and columns of the joint probability
		GImageMiscOps.fill(entropyRight,0);
		for (int row = 0; row < entropyJoint.height; row++) {
			int idx = row * entropyJoint.width;
			float sumRow = 0;
			for (int col = 0; col < entropyJoint.width; col++, idx++) {
				float v = entropyJoint.data[idx];
				sumRow += v;
				entropyRight.data[col] += v;
			}
			entropyLeft.data[row] = sumRow;
		}
	}

	/**
	 * Compute Entropy from the already computed probabilities
	 */
	void computeEntropy() {

		// Compute Joint Entropy Eq. 5
		// H = -(1/n)*log(I*G)*G
		// Supposedly this is effectively Parezen Estimation
		ConvolveImageNormalized.horizontal(smoothKernel, entropyJoint, smoothWork);
		ConvolveImageNormalized.vertical(smoothKernel, smoothWork, entropyJoint);
		PixelMath.log(entropyJoint, eps, entropyJoint); // NOTE: The paper says replace zero with EPS not add to all
		ConvolveImageNormalized.horizontal(smoothKernel, entropyJoint, smoothWork);
		ConvolveImageNormalized.vertical(smoothKernel, smoothWork, entropyJoint);
		PixelMath.divide(entropyJoint,-totalDispPixels, entropyJoint);

		// Compute entropy for each image using a similar approach
		ConvolveImageNormalized.horizontal(smoothKernel, entropyLeft, smoothWork);
		PixelMath.log(smoothWork,eps,smoothWork);
		ConvolveImageNormalized.horizontal(smoothKernel, smoothWork, entropyLeft);
		PixelMath.divide(entropyLeft,-totalDispPixels, entropyLeft);

		ConvolveImageNormalized.horizontal(smoothKernel, entropyRight, smoothWork);
		PixelMath.log(smoothWork,eps,smoothWork);
		ConvolveImageNormalized.horizontal(smoothKernel, smoothWork, entropyRight);
		PixelMath.divide(entropyRight,-totalDispPixels, entropyRight);
	}

	/**
	 * Precompute cost scaled to have a range of 0 to maxCost, inclusive
	 */
	public void precomputeScaledCost( int maxCost ) {
		final int N = maxHistogramValue;

		float minValue = Float.MAX_VALUE;
		float maxValue = -Float.MAX_VALUE;

		//NOTE: Is there a way to compute this without going through exaustively?
		for (int y = 0; y < N; y++) {
			for (int x = 0; x < N; x++) {
				float v = -(entropyLeft.data[y] + entropyRight.data[x] - entropyJoint.unsafe_get(x,y));
				if( minValue > v )
					minValue = v;
				if( maxValue < v )
					maxValue = v;
			}
		}
		float rangeValue = maxValue-minValue;

		for (int left = 0; left < N; left++) {
			for (int right = 0; right < N; right++) {
				float v = -(entropyLeft.data[left] + entropyRight.data[right] - entropyJoint.unsafe_get(right,left));
				scaledCost.data[left*N+right] = (short)(maxCost*(v-minValue)/rangeValue);
			}
		}
	}

	/**
	 * Scales input pixel intensity to histogram range
	 */
	final int scalePixelValue(int value ) {
		return maxHistogramValue*value/maxPixelValue;
	}

	public float getEps() {
		return eps;
	}

	public void setEps(float eps) {
		this.eps = eps;
	}
}
