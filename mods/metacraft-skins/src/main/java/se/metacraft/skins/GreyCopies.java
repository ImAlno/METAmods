package se.metacraft.skins;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * The greyscale copies of a Dyeable Hat's tinted textures, which take the dye in its dyed model.
 * Each pixel keeps its alpha and becomes a grey around one light shade, so a dye reads close to
 * true: {@code 205 + (lum(pixel) − lum(average)) × 1.25}, rounded and clamped to 0–255, where
 * {@code lum(r, g, b) = 0.299 r + 0.587 g + 0.114 b} and the average is the mean colour of every
 * visible pixel of all the hat's tinted textures together (the Dyeable Hat prototype's mapping).
 * The PNGs are written here, not by ImageIO, so the same textures always give the same bytes.
 */
final class GreyCopies {
	private GreyCopies() {}

	/** What a tinted texture's average maps to: light, so dyes read close to true. */
	private static final double GREY_BASE = 205;
	/** How strongly brightness differences carry into the grey. */
	private static final double SHADE = 1.25;
	private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

	/** A texture that isn't a PNG ImageIO can read. */
	static final class Unreadable extends Exception {
		final String name;

		Unreadable(String name, String reason) {
			super(reason, null, false, false);
			this.name = name;
		}
	}

	/** The grey copy of each texture ({@code pngs}: PNG bytes by name), by the same name, in the same order. */
	static Map<String, byte[]> of(Map<String, byte[]> pngs) throws Unreadable {
		Map<String, BufferedImage> images = new LinkedHashMap<>();
		for (var png : pngs.entrySet()) images.put(png.getKey(), read(png.getKey(), png.getValue()));

		long red = 0, green = 0, blue = 0, visible = 0;
		for (BufferedImage image : images.values()) {
			for (int argb : pixels(image)) {
				if (argb >>> 24 == 0) continue;
				red += argb >> 16 & 0xFF;
				green += argb >> 8 & 0xFF;
				blue += argb & 0xFF;
				visible++;
			}
		}
		// With nothing visible there's nothing to shade against; white, as in the prototype.
		double average = visible == 0 ? 255 : lum((double) red / visible, (double) green / visible, (double) blue / visible);

		Map<String, byte[]> greys = new LinkedHashMap<>();
		for (var image : images.entrySet()) {
			int[] pixels = pixels(image.getValue());
			for (int i = 0; i < pixels.length; i++) {
				int argb = pixels[i];
				int grey = (int) Math.clamp(Math.rint(GREY_BASE + (lum(argb >> 16 & 0xFF, argb >> 8 & 0xFF, argb & 0xFF) - average) * SHADE), 0, 255);
				pixels[i] = argb & 0xFF000000 | grey << 16 | grey << 8 | grey;
			}
			greys.put(image.getKey(), png(image.getValue().getWidth(), image.getValue().getHeight(), pixels));
		}
		return greys;
	}

	private static double lum(double r, double g, double b) {
		return 0.299 * r + 0.587 * g + 0.114 * b;
	}

	private static BufferedImage read(String name, byte[] png) throws Unreadable {
		try {
			BufferedImage image = ImageIO.read(new MemoryCacheImageInputStream(new ByteArrayInputStream(png)));
			if (image == null) throw new Unreadable(name, "not a PNG");
			return image;
		} catch (IOException | RuntimeException e) {
			throw new Unreadable(name, "not a readable PNG: " + e.getMessage());
		}
	}

	private static int[] pixels(BufferedImage image) {
		return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
	}

	/** An 8-bit RGBA PNG of these ARGB pixels, row by row: no filters, no extra chunks, so always the same bytes. */
	private static byte[] png(int width, int height, int[] argb) {
		byte[] raw = new byte[height * (1 + width * 4)];
		int at = 0;
		for (int y = 0; y < height; y++) {
			raw[at++] = 0;   // filter: none
			for (int x = 0; x < width; x++) {
				int pixel = argb[y * width + x];
				raw[at++] = (byte) (pixel >> 16);
				raw[at++] = (byte) (pixel >> 8);
				raw[at++] = (byte) pixel;
				raw[at++] = (byte) (pixel >>> 24);
			}
		}
		Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
		deflater.setInput(raw);
		deflater.finish();
		ByteArrayOutputStream compressed = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		while (!deflater.finished()) compressed.write(buffer, 0, deflater.deflate(buffer));
		deflater.end();

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.writeBytes(SIGNATURE);
		// IHDR: size, 8 bits a channel, colour type 6 (RGBA), deflate, no filter method, no interlace.
		chunk(out, "IHDR", ByteBuffer.allocate(13).putInt(width).putInt(height).put((byte) 8).put((byte) 6).put((byte) 0).put((byte) 0).put((byte) 0).array());
		chunk(out, "IDAT", compressed.toByteArray());
		chunk(out, "IEND", new byte[0]);
		return out.toByteArray();
	}

	private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
		byte[] name = type.getBytes(StandardCharsets.US_ASCII);
		CRC32 crc = new CRC32();
		crc.update(name);
		crc.update(data);
		out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
		out.writeBytes(name);
		out.writeBytes(data);
		out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
	}
}
