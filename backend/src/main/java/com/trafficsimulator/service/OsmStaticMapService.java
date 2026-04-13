package com.trafficsimulator.service;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import javax.imageio.ImageIO;

import org.springframework.stereotype.Service;

import com.trafficsimulator.dto.BboxRequest;

import lombok.extern.slf4j.Slf4j;

/**
 * Composes a static PNG image of an OSM bounding box by fetching slippy-map tiles server-side
 * (no browser CORS issues) and stitching them together.
 */
@Service
@Slf4j
public class OsmStaticMapService {

    private static final int TILE_SIZE = 256;
    private static final int MAX_ZOOM = 18;
    // Cap composed image dimensions so Claude vision request stays small.
    private static final int MAX_IMAGE_DIM = 1280;
    private static final String TILE_URL = "https://tile.openstreetmap.org/%d/%d/%d.png";
    private static final String USER_AGENT = "TrafficSimulator/1.0 (vision-bbox)";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public byte[] composeBboxPng(BboxRequest bbox) throws IOException {
        int zoom = pickZoom(bbox);
        double xMinD = lonToTileX(bbox.west(), zoom);
        double xMaxD = lonToTileX(bbox.east(), zoom);
        double yMinD = latToTileY(bbox.north(), zoom);
        double yMaxD = latToTileY(bbox.south(), zoom);

        int xMin = (int) Math.floor(xMinD);
        int xMax = (int) Math.floor(xMaxD);
        int yMin = (int) Math.floor(yMinD);
        int yMax = (int) Math.floor(yMaxD);

        int gridW = xMax - xMin + 1;
        int gridH = yMax - yMin + 1;
        BufferedImage canvas = new BufferedImage(
                gridW * TILE_SIZE, gridH * TILE_SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        try {
            for (int tx = xMin; tx <= xMax; tx++) {
                for (int ty = yMin; ty <= yMax; ty++) {
                    BufferedImage tile = fetchTile(zoom, tx, ty);
                    g.drawImage(tile, (tx - xMin) * TILE_SIZE, (ty - yMin) * TILE_SIZE, null);
                }
            }
        } finally {
            g.dispose();
        }

        // Crop to the actual bbox pixel rectangle within the tile grid.
        int cropX = (int) Math.round((xMinD - xMin) * TILE_SIZE);
        int cropY = (int) Math.round((yMinD - yMin) * TILE_SIZE);
        int cropW = (int) Math.round((xMaxD - xMinD) * TILE_SIZE);
        int cropH = (int) Math.round((yMaxD - yMinD) * TILE_SIZE);
        cropW = Math.max(1, Math.min(cropW, canvas.getWidth() - cropX));
        cropH = Math.max(1, Math.min(cropH, canvas.getHeight() - cropY));
        BufferedImage cropped = canvas.getSubimage(cropX, cropY, cropW, cropH);

        log.info(
                "Composed OSM static map: zoom={} tiles={}x{} cropped={}x{} bbox={}",
                zoom, gridW, gridH, cropW, cropH, bbox);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(cropped, "PNG", baos);
        return baos.toByteArray();
    }

    private int pickZoom(BboxRequest bbox) {
        // Choose zoom such that bbox spans roughly MAX_IMAGE_DIM pixels in width.
        double lonSpan = Math.max(1e-6, bbox.east() - bbox.west());
        // pixelsPerDegLon at zoom z = TILE_SIZE * 2^z / 360
        // want pixelsPerDegLon * lonSpan ≈ MAX_IMAGE_DIM
        double targetTiles = MAX_IMAGE_DIM / (double) TILE_SIZE;
        double n = targetTiles * 360.0 / lonSpan;
        int zoom = (int) Math.floor(Math.log(n) / Math.log(2));
        return Math.max(0, Math.min(MAX_ZOOM, zoom));
    }

    private static double lonToTileX(double lon, int zoom) {
        return (lon + 180.0) / 360.0 * (1 << zoom);
    }

    private static double latToTileY(double lat, int zoom) {
        double rad = Math.toRadians(lat);
        return (1.0 - Math.log(Math.tan(rad) + 1.0 / Math.cos(rad)) / Math.PI) / 2.0 * (1 << zoom);
    }

    private BufferedImage fetchTile(int z, int x, int y) throws IOException {
        String url = String.format(TILE_URL, z, x, y);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        try {
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                throw new IOException("Tile " + url + " HTTP " + resp.statusCode());
            }
            return ImageIO.read(new java.io.ByteArrayInputStream(resp.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Tile fetch interrupted: " + url, e);
        }
    }
}
