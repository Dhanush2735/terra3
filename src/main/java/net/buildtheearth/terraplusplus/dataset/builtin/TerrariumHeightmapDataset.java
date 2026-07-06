package net.buildtheearth.terraplusplus.dataset.builtin;

import net.buildtheearth.terraplusplus.dataset.HeightmapDataset;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;

public class TerrariumHeightmapDataset extends HeightmapDataset {
    
    private final String baseUrl = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium";

    public TerrariumHeightmapDataset(String ignoredUrl, int cacheSize, ExecutorService executor) {
        super(cacheSize, executor);
    }

    @Override
    public double[] loadTile(int zoom, int x, int y) throws Exception {
        String urlStr = String.format("%s/%d/%d/%d.png", baseUrl, zoom, x, y);
        URL url = new URL(urlStr);
        
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0"); // Bypass Amazon Block

        BufferedImage image = ImageIO.read(conn.getInputStream());
        if (image == null) return new double[256 * 256];

        int width = image.getWidth();
        int height = image.getHeight();
        double[] data = new double[width * height];
        
        for (int py = 0; py < height; py++) {
            for (int px = 0; px < width; px++) {
                int rgb = image.getRGB(px, py);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                
                double meters = (r * 256.0 + g + b / 256.0) - 32768.0;
                data[py * width + px] = meters;
            }
        }
        
        System.out.println("Terra++ SUCCESS: Downloaded " + x + "," + y);
        return data;
    }
}