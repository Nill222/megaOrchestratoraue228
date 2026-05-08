package com.example.iml.service;

import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;

import java.util.Base64;

public class OpenCvImageCodec {

    public Mat decodeBase64(String base64) {
        byte[] bytes = Base64.getDecoder().decode(base64);
        MatOfByte mob = new MatOfByte(bytes);
        try {
            return Imgcodecs.imdecode(mob, Imgcodecs.IMREAD_COLOR);
        } finally {
            mob.release();
        }
    }

    public String encodeBase64Png(Mat mat) {
        MatOfByte buf = new MatOfByte();
        try {
            if (!Imgcodecs.imencode(".png", mat, buf)) {
                throw new IllegalStateException("Failed to encode PNG");
            }
            return Base64.getEncoder().encodeToString(buf.toArray());
        } finally {
            buf.release();
        }
    }
}
