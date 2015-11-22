package com.kac.upit;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.ClipboardOwner;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.prefs.Preferences;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.swing.Box;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFormattedTextField;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.text.NumberFormatter;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.Protocol;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.event.ProgressEvent;
import com.amazonaws.event.ProgressListener;
import com.amazonaws.regions.RegionUtils;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.AccessControlList;
import com.amazonaws.services.s3.model.GroupGrantee;
import com.amazonaws.services.s3.model.Permission;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.services.s3.transfer.Upload;

@SuppressWarnings("serial")
public class App extends JFrame implements ActionListener, ProgressListener, ClipboardOwner {
	static class Record {
		File file;
		long size;
	}

	static class MyModel extends AbstractTableModel {
		List<Record> records = new ArrayList<App.Record>();
		static final String[] columns = { "nazwa", "rozmiar", "usuń" };

		@Override
		public String getColumnName(int column) {
			return columns[column];
		}

		public int getColumnCount() {
			return columns.length;
		}

		public Object getValueAt(int row, int column) {
			Record r = (Record) records.get(row);
			switch (column) {
			case 0:
				return r.file.getName() + (r.file.isDirectory() ? "/" : "");
			case 1:
				return sizeToFriendly(r.size);
			default:
				return "X";
			}
		}

		public int getRowCount() {
			return records.size();
		}
	}

	static void add(ZipOutputStream zos, File base, File f, byte[] buff, boolean scaleImages,
			int targetWidth, String format) throws IOException {
		if (f.isDirectory()) {
			for (File c : f.listFiles()) {
				add(zos, base, c, buff, scaleImages, targetWidth, format);
			}
		} else if (f.isFile()) {
			ImageScaler.Scaled scaled = null;
			if (scaleImages) {
				scaled = ImageScaler.scaleImage(f, targetWidth, format);
			}
			Path p;
			InputStream is;
			if (scaled != null) {
				p = new File(f.getParentFile(), f.getName() + ".scaled." + scaled.format).toPath();
				is = new ByteArrayInputStream(scaled.stream);
			} else {
				p = f.toPath();
				is = new FileInputStream(f);
			}
			p = base.toPath().relativize(p);
			zos.putNextEntry(new ZipEntry(p.toString()));
			int r;
			while ((r = is.read(buff)) != -1) {
				zos.write(buff, 0, r);
			}
			is.close();
		}
	}

	static final DateFormat DF = new SimpleDateFormat("yy-MM-dd_HH-mm-ss");

	public static String sizeToFriendly(long size) {
		String[] post = { "", "KB", "MB", "GB", "TB" };
		int pi = 0;
		while (size > 999) {
			size /= 1024;
			pi++;
		}
		return size + " " + post[pi];
	}

	public static long calcRecur(File f) {
		long r = 0;
		if (f.isDirectory()) {
			for (File c : f.listFiles()) {
				r += calcRecur(c);
			}
		} else {
			r = f.length();
		}
		return r;
	}

	static DataFlavor LIST_DATA_FLAVOR;

	static {
		try {
			LIST_DATA_FLAVOR = new DataFlavor(
					"text/uri-list; class=java.lang.String; charset=Unicode");
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
		}
	}

	@SuppressWarnings("unchecked")
	public static File[] getFiles(Transferable t) {
		List<File> files = new ArrayList<File>();
		if (t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
			try {
				files.addAll((List<File>) t.getTransferData(DataFlavor.javaFileListFlavor));
			} catch (Exception e) {
				e.printStackTrace();
			}
		} else if (t.isDataFlavorSupported(LIST_DATA_FLAVOR))
			try {
				String s = (String) t.getTransferData(LIST_DATA_FLAVOR);
				String[] filez = s.split("\r?\n");
				files = new ArrayList<File>();
				for (String uri : filez) {
					files.add(new File(new URI(uri)));
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		return files.toArray(new File[files.size()]);
	}

	public static void main(String[] args) {
		java.security.Security.setProperty("networkaddress.cache.ttl", "60");
		SwingUtilities.invokeLater(new Runnable() {

			public void run() {
				App app = new App();
				app.setAlwaysOnTop(true);
				app.pack();
				app.locateTopRight();
				app.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
				app.setVisible(true);
			}
		});
	}

	MyModel model;
	JTable table;
	JScrollPane pane;
	File currentZip;

	Desktop desktop;

	JButton up, cancel;
	JProgressBar progress;

	boolean working;

	AmazonS3Client s3Client;
	TransferManager manager;
	Upload upload;

	String bucket;
	String prefix;

	String publicURL;

	boolean aborted;

	Dimension goodSize = new Dimension(300, 160);

	Preferences prefs;
	public static String P_SCALE_IMAGES = "scaleImages";
	public static String P_TARGET_WIDTH = "targetWidth";
	public static String P_TO_JPG = "toJPG";
	boolean d_scaleImages = true;
	int d_targetWidth = 800;
	boolean d_toJPG = true;
	JCheckBox p_scaleImages;
	JFormattedTextField p_targetWidth;
	JCheckBox p_toJPG;

	Properties props;

	public App() {
		super("UpIt-Amasz");
		props = new Properties();
		try {
			props.load(new FileInputStream(
					Paths.get(System.getProperty("user.home"), ".kac-amasz", "upit.properties")
							.toFile()));
		} catch (Exception e) {
			JOptionPane.showMessageDialog(this, e.toString(), "Błąd", JOptionPane.ERROR_MESSAGE);
			System.exit(1);
		}
		setLayout(new BorderLayout());
		model = new MyModel();
		table = new JTable(model);
		table.setMinimumSize(new Dimension(goodSize.width, goodSize.height - 20));
		pane = new JScrollPane(table, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
				JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		add(BorderLayout.CENTER, pane);

		table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

		DefaultTableCellRenderer rightRenderer = new DefaultTableCellRenderer();
		rightRenderer.setHorizontalAlignment(SwingConstants.RIGHT);
		table.getColumnModel().getColumn(1).setCellRenderer(rightRenderer);
		table.getColumnModel().getColumn(1).setMaxWidth(80);

		table.getColumnModel().getColumn(2).setMaxWidth(40);

		table.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				// System.out.println(e);
				if (e.getClickCount() == 2) {
					int row = table.rowAtPoint(e.getPoint()),
							col = table.columnAtPoint(e.getPoint());
					if (row >= 0 && col >= 0) {
						row = table.convertRowIndexToModel(row);
						col = table.convertColumnIndexToModel(col);
						if (col == 2 && !working) {
							model.records.remove(row);
							model.fireTableRowsDeleted(row, row);
						} else if (col == 0 && desktop != null) {
							try {
								desktop.open(model.records.get(row).file);
							} catch (IOException e1) {
								// TODO Auto-generated catch block
								e1.printStackTrace();
							}
						}
					}
				}
			}
		});

		if (Desktop.isDesktopSupported()) {
			desktop = Desktop.getDesktop();
		}

		setDropTarget(new DropTarget() {
			@Override
			public synchronized void drop(DropTargetDropEvent dtde) {
				if (!working) {
					dtde.acceptDrop(DnDConstants.ACTION_LINK);
					Transferable t = dtde.getTransferable();
					File[] files = getFiles(t);
					addFiles(files);
				}
			}
		});

		JComponent bottom = new JPanel();
		bottom = Box.createVerticalBox();
		JPanel panel = new JPanel();
		up = new JButton("up");
		up.addActionListener(this);
		panel.add(up);
		progress = new JProgressBar(0, 100);
		panel.add(progress);
		cancel = new JButton("cancel");
		cancel.addActionListener(this);
		panel.add(cancel);

		bottom.add(panel);

		panel = new JPanel();
		prefs = Preferences.userNodeForPackage(App.class);
		panel.add(p_scaleImages = new JCheckBox("scale",
				prefs.getBoolean(P_SCALE_IMAGES, d_scaleImages)));
		panel.add(p_targetWidth = new JFormattedTextField(new NumberFormatter()));
		panel.add(p_toJPG = new JCheckBox("jpg", prefs.getBoolean(P_TO_JPG, d_toJPG)));
		p_targetWidth.setColumns(5);
		p_targetWidth.setValue(prefs.getInt(P_TARGET_WIDTH, d_targetWidth));
		p_targetWidth.setFocusLostBehavior(JFormattedTextField.COMMIT_OR_REVERT);

		p_scaleImages.addItemListener(new ItemListener() {

			public void itemStateChanged(ItemEvent e) {
				prefs.putBoolean(P_SCALE_IMAGES, p_scaleImages.isSelected());
			}
		});
		p_targetWidth.addPropertyChangeListener("value", new PropertyChangeListener() {

			public void propertyChange(PropertyChangeEvent evt) {
				System.out.println(evt);
				if (evt.getNewValue() != null) {
					System.out.println(((Number) evt.getNewValue()).intValue());
					prefs.putInt(P_TARGET_WIDTH, ((Number) evt.getNewValue()).intValue());
				}
			}
		});
		p_toJPG.addItemListener(new ItemListener() {

			public void itemStateChanged(ItemEvent e) {
				prefs.putBoolean(P_TO_JPG, p_toJPG.isSelected());
			}
		});
		bottom.add(panel);

		add(BorderLayout.SOUTH, bottom);

		s3Client = new AmazonS3Client(
				new BasicAWSCredentials(props.getProperty("aws_access_key_id"),
						props.getProperty("aws_secret_access_key")),
				new ClientConfiguration().withProtocol(Protocol.HTTPS));

		s3Client.setRegion(RegionUtils.getRegion(props.getProperty("region")));
		manager = new TransferManager(s3Client, Executors.newFixedThreadPool(3));
		bucket = props.getProperty("bucket");
		prefix = props.getProperty("prefix");

		Image image = new ImageIcon(
				getClass().getClassLoader().getResource("com/kac/upit/upit.png")).getImage();
		setIconImage(image);

	}

	void locateTopRight() {
		setSize(goodSize);
		GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
		GraphicsDevice defaultScreen = ge.getDefaultScreenDevice();
		Rectangle rect = defaultScreen.getDefaultConfiguration().getBounds();
		int x = (int) rect.getMaxX() - getWidth();
		int y = 50;
		setLocation(x, y);
	}

	public void actionPerformed(ActionEvent e) {
		if (e.getActionCommand().equals("up")) {
			if (model.records.size() == 0) {
				if (publicURL != null) {
					toClipboard(publicURL);
				}
				return;
			}
			up.setEnabled(false);
			cancel.setEnabled(true);
			working = true;
			new Thread() {

				public void run() {
					try {
						currentZip = File.createTempFile("upit", ".zip");
						currentZip.deleteOnExit();
						ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(currentZip));
						byte[] buff = new byte[1024 * 1024];
						boolean scaleImages = prefs.getBoolean(P_SCALE_IMAGES, d_scaleImages);
						int targetWidth = prefs.getInt(P_TARGET_WIDTH, d_targetWidth);
						boolean toJPG = prefs.getBoolean(P_TO_JPG, d_toJPG);
						for (Record r : model.records) {
							add(zos, r.file.getParentFile(), r.file, buff, scaleImages, targetWidth,
									toJPG ? "jpg" : null);
						}
						zos.closeEntry();
						zos.flush();
						zos.close();
						System.out.println(currentZip);

						AccessControlList acl = new AccessControlList();
						acl.grantPermission(GroupGrantee.AllUsers, Permission.Read);

						String n = prefix + DF.format(new Date());
						Random r = new Random();
						n += "_";
						for (int i = 0; i < 8; i++) {
							n += r.nextInt(10);
						}
						n += ".zip";
						PutObjectRequest req = new PutObjectRequest(bucket, n, currentZip)
								.withAccessControlList(acl);

						upload = manager.upload(req);
						upload.addProgressListener(App.this);
						// publicURL = "https://" + bucket + ".s3-" + region +
						// ".amazonaws.com/" + n;
						publicURL = "https://" + bucket + ".s3.amazonaws.com/" + n;
						// publicURL = "https://s3.amazonaws.com/" + bucket +
						// "/" + n;
						System.out.println(publicURL);
						toClipboard(publicURL);

						try {
							upload.waitForCompletion();
							model.records.clear();
							model.fireTableDataChanged();
						} catch (CancellationException ce) {
							System.err.println("aborted: " + aborted + " " + ce);
						}
					} catch (Exception ex) {
						ex.printStackTrace();
					} finally {
						if (upload != null) {
							upload = null;
						}
						if (currentZip != null) {
							currentZip.deleteOnExit();
							currentZip = null;
						}

						aborted = false;
						working = false;
						up.setEnabled(true);
						cancel.setEnabled(false);
					}

				}
			}.start();

		} else if (e.getActionCommand().equals("cancel")) {
			if (upload != null) {
				aborted = true;
				upload.abort();
			}
		}

	}

	public void addFiles(File... files) {
		boolean change = false;
		files: for (File c : files) {
			for (Record r : model.records) {
				if (r.file.equals(c)) {
					continue files;
				}
			}
			App.Record rec = new App.Record();
			rec.file = c;
			rec.size = App.calcRecur(c);

			model.records.add(0, rec);
			change = true;
		}
		if (change) {
			model.fireTableDataChanged();
		}
	}

	public void progressChanged(ProgressEvent progressEvent) {
		Upload ul = upload;
		if (ul != null) {
			progress.setValue((int) Math.round(ul.getProgress().getPercentTransferred()));
		}
	}

	// ClipboardOwner
	public void lostOwnership(Clipboard clipboard, Transferable contents) {
	}

	void toClipboard(String content) {
		StringSelection stringSelection = new StringSelection(content);
		Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
		clipboard.setContents(stringSelection, this);
	}

}
