package com.bmsedge.inventory.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@Component
public class QRCodeGenerator {

    @Value("${app.qr-code-path:/tmp/qr-codes}")
    private String qrCodeBasePath;

    private boolean zxingAvailable = false;

    public QRCodeGenerator() {
        // Check if ZXing is available at runtime
        try {
            Class.forName("com.google.zxing.qrcode.QRCodeWriter");
            this.zxingAvailable = true;
        } catch (ClassNotFoundException e) {
            this.zxingAvailable = false;
        }
    }

    public String generateQRCode(Long itemId, String itemName) throws IOException {
        if (zxingAvailable) {
            return generateQRCodeWithZXing(itemId, itemName);
        } else {
            return generateQRCodeFallback(itemId, itemName);
        }
    }

    private String generateQRCodeWithZXing(Long itemId, String itemName) throws IOException {
        try {
            // Dynamic import to avoid compilation issues
            String qrCodeData = generateQRCodeData(itemId, itemName);

            // Create directory if it doesn't exist
            Path directory = Paths.get(qrCodeBasePath);
            if (!Files.exists(directory)) {
                Files.createDirectories(directory);
            }

            // Use reflection to avoid compilation dependency
            Class<?> barcodeFormatClass = Class.forName("com.google.zxing.BarcodeFormat");
            Class<?> qrCodeWriterClass = Class.forName("com.google.zxing.qrcode.QRCodeWriter");
            Class<?> matrixToImageWriterClass = Class.forName("com.google.zxing.client.j2se.MatrixToImageWriter");

            Object qrCodeWriter = qrCodeWriterClass.getDeclaredConstructor().newInstance();
            Object qrCodeFormat = Enum.valueOf((Class<Enum>) barcodeFormatClass, "QR_CODE");

            // Generate QR code
            Object bitMatrix = qrCodeWriterClass.getMethod("encode", String.class, barcodeFormatClass, int.class, int.class)
                    .invoke(qrCodeWriter, qrCodeData, qrCodeFormat, 300, 300);

            // Save QR code as image
            String fileName = String.format("item_%d_qr.png", itemId);
            Path path = Paths.get(qrCodeBasePath, fileName);

            matrixToImageWriterClass.getMethod("writeToPath",
                            Class.forName("com.google.zxing.common.BitMatrix"),
                            String.class,
                            Path.class)
                    .invoke(null, bitMatrix, "PNG", path);

            return path.toString();

        } catch (Exception e) {
            // Fallback if ZXing fails
            return generateQRCodeFallback(itemId, itemName);
        }
    }

    private String generateQRCodeFallback(Long itemId, String itemName) throws IOException {
        // Create directory if it doesn't exist
        Path directory = Paths.get(qrCodeBasePath);
        if (!Files.exists(directory)) {
            Files.createDirectories(directory);
        }

        // Generate QR code data file with URL for online generation
        String qrCodeData = generateQRCodeData(itemId, itemName);
        String fileName = String.format("item_%d_qr_info.txt", itemId);
        Path path = Paths.get(qrCodeBasePath, fileName);

        Map<String, String> qrInfo = new HashMap<>();
        qrInfo.put("itemId", itemId.toString());
        qrInfo.put("itemName", itemName);
        qrInfo.put("qrCodeData", qrCodeData);
        qrInfo.put("qrCodeUrl", generateQRCodeUrl(qrCodeData));
        qrInfo.put("timestamp", String.valueOf(System.currentTimeMillis()));

        StringBuilder content = new StringBuilder();
        content.append("QR Code Information for Item: ").append(itemName).append("\n");
        content.append("Item ID: ").append(itemId).append("\n");
        content.append("QR Code Data: ").append(qrCodeData).append("\n");
        content.append("Online QR Code URL: ").append(qrInfo.get("qrCodeUrl")).append("\n");
        content.append("Generated: ").append(java.time.LocalDateTime.now()).append("\n");
        content.append("\nInstructions:\n");
        content.append("1. Use the QR Code URL above to generate the actual QR code image\n");
        content.append("2. Or scan this data directly: ").append(qrCodeData).append("\n");

        Files.write(path, content.toString().getBytes());
        return path.toString();
    }

    public String generateQRCodeData(Long itemId, String itemName) {
        String rawData = String.format("ITEM_ID:%d;ITEM_NAME:%s;TIMESTAMP:%d",
                itemId, itemName, System.currentTimeMillis());
        return Base64.getEncoder().encodeToString(rawData.getBytes());
    }

    private String generateQRCodeUrl(String qrCodeData) {
        // Use QR Server API for online generation
        try {
            String encodedData = java.net.URLEncoder.encode(qrCodeData, "UTF-8");
            return String.format("https://api.qrserver.com/v1/create-qr-code/?size=300x300&data=%s", encodedData);
        } catch (Exception e) {
            return "https://api.qrserver.com/v1/create-qr-code/?size=300x300&data=" + qrCodeData;
        }
    }

    public boolean isZXingAvailable() {
        return zxingAvailable;
    }
}