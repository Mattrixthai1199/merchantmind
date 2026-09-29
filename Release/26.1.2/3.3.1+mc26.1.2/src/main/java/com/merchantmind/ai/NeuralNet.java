package com.merchantmind.ai;

import java.util.Random;

public final class NeuralNet {
   private final int inputSize;
   private final int[] layerSizes;
   private final double[][][] weights;
   private final double[][] biases;

   public NeuralNet(int n, long l, int... nArray) {
      this.inputSize = n;
      this.layerSizes = nArray;
      this.weights = new double[nArray.length][][];
      this.biases = new double[nArray.length][];
      Random random = new Random(l);
      int n2 = n;

      for (int i = 0; i < nArray.length; i++) {
         int n3 = nArray[i];
         this.weights[i] = new double[n3][n2];
         this.biases[i] = new double[n3];
         double d = Math.sqrt(2.0 / n2);

         for (int j = 0; j < n3; j++) {
            for (int k = 0; k < n2; k++) {
               this.weights[i][j][k] = (random.nextDouble() * 2.0 - 1.0) * d;
            }

            this.biases[i][j] = (random.nextDouble() * 2.0 - 1.0) * 0.1;
         }

         n2 = n3;
      }
   }

   public void encourageFeature(int n, double d) {
      for (int i = 0; i < this.weights[0].length; i++) {
         double[] dArray = this.weights[0][i];
         dArray[n] += d * (i % 2 == 0 ? 1.0 : 0.5);
      }
   }

   public double[] forward(double[] dArray) {
      if (dArray.length != this.inputSize) {
         throw new IllegalArgumentException("expected " + this.inputSize + " inputs, got " + dArray.length);
      } else {
         double[] dArray2 = dArray;

         for (int i = 0; i < this.layerSizes.length; i++) {
            double[] dArray3 = new double[this.layerSizes[i]];

            for (int j = 0; j < this.layerSizes[i]; j++) {
               double d = this.biases[i][j];
               double[] dArray4 = this.weights[i][j];

               for (int n = 0; n < dArray2.length; n++) {
                  d += dArray4[n] * dArray2[n];
               }

               boolean isOutputLayer = i == this.layerSizes.length - 1;
               dArray3[j] = isOutputLayer ? sigmoid(d) : Math.tanh(d);
            }

            dArray2 = dArray3;
         }

         return dArray2;
      }
   }

   public double score(double[] dArray) {
      return this.forward(dArray)[0];
   }

   private static double sigmoid(double d) {
      return 1.0 / (1.0 + Math.exp(-d));
   }
}
