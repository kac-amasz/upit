package com.kac.upit;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;

import javax.imageio.ImageIO;

import org.imgscalr.Scalr;

public class ImageScaler {
	static final String[] formats = { "jpg", "gif", "bmp", "png" };

	static final class Scaled {
		public byte[] stream;
		public String format;

		public Scaled(byte[] stream, String format) {
			super();
			this.stream = stream;
			this.format = format;
		}
	}

	public static Scaled scaleImage(File inFile, int targetWidth, String format) {
		String formatFound = null;
		{
			String name = inFile.getName().toLowerCase();
			for (String f : formats) {
				if (name.endsWith("." + f)) {
					formatFound = f;
				}
			}
		}
		if (formatFound != null) {
			try {
				BufferedImage in = ImageIO.read(inFile);
				if (in.getWidth() > targetWidth
						|| (format != null && !formatFound.equals(format))) {
					if (format == null) {
						format = formatFound;
					}
					if (in.getWidth() > targetWidth) {
						in = Scalr.resize(in, targetWidth);
					}
					ByteArrayOutputStream bos = new ByteArrayOutputStream();
					ImageIO.write(in, format, bos);
					return new Scaled(bos.toByteArray(), format);
				}
			} catch (Exception ex) {
				ex.printStackTrace();
			}
		}
		return null;
	}
}