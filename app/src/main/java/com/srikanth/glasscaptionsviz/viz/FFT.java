package com.srikanth.glasscaptionsviz.viz;

public class FFT {
    public final int size;
    private final int[] rev;
    private final double[] cos;
    private final double[] sin;

    public FFT(int n) {
        int p = 1;
        while (p < n) p <<= 1;
        size = p;
        rev = new int[size];
        int log = 0; while ((1 << log) < size) log++;
        for (int i=0;i<size;i++) {
            rev[i] = Integer.reverse(i) >>> (32 - log);
        }
        cos = new double[size/2];
        sin = new double[size/2];
        for (int i=0;i<size/2;i++) {
            double ang = -2*Math.PI*i/size;
            cos[i] = Math.cos(ang);
            sin[i] = Math.sin(ang);
        }
    }

    public void fft(double[] re, double[] im) {
        int n = size;
        for (int i=0;i<n;i++) {
            int j = rev[i];
            if (j < i) {
                double tr = re[i]; re[i] = re[j]; re[j] = tr;
                double ti = im[i]; im[i] = im[j]; im[j] = ti;
            }
        }
        for (int len=2; len<=n; len<<=1) {
            int half = len>>1;
            int step = n/len;
            for (int i=0; i<n; i+=len) {
                for (int j=0; j<half; j++) {
                    double wr = cos[j*step];
                    double wi = sin[j*step];
                    int k = i + j + half;
                    double xr = re[k]*wr - im[k]*wi;
                    double xi = re[k]*wi + im[k]*wr;
                    re[k] = re[i+j] - xr;
                    im[k] = im[i+j] - xi;
                    re[i+j] += xr;
                    im[i+j] += xi;
                }
            }
        }
    }
}
