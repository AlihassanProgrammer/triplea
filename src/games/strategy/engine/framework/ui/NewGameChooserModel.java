package games.strategy.engine.framework.ui;

import java.awt.Component;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.swing.DefaultListModel;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import games.strategy.debug.ClientLogger;
import games.strategy.engine.data.EngineVersionException;
import games.strategy.engine.data.GameParseException;
import games.strategy.engine.framework.GameRunner2;
import games.strategy.engine.framework.startup.ui.MainFrame;
import games.strategy.util.ClassLoaderUtil;

public class NewGameChooserModel extends DefaultListModel {
  private static final long serialVersionUID = -2044689419834812524L;
  private final ClearGameChooserCacheMessenger clearCacheMessenger;

  private enum ZipProcessingResult {
    SUCCESS, ERROR
  }


  public NewGameChooserModel(ClearGameChooserCacheMessenger clearCacheMessenger) {
    this.clearCacheMessenger = clearCacheMessenger;
    populate();
  }

  @Override
  public NewGameChooserEntry get(final int i) {
    return (NewGameChooserEntry) super.get(i);
  }

  public static File getDefaultMapsDir() {
    return new File(GameRunner2.getRootFolder(), "maps");
  }


  private void populate() {
    final Set<NewGameChooserEntry> parsedMapSet = parseMapFiles();

    final List<NewGameChooserEntry> entries = Lists.newArrayList(parsedMapSet);
    Collections.sort(entries, NewGameChooserEntry.getComparator());

    for (final NewGameChooserEntry entry : entries) {
      addElement(entry);
    }
  }

  private static List<File> allMapFiles() {
    final List<File> rVal = new ArrayList<File>();
    // prioritize user maps folder over root folder
    rVal.addAll(safeListFiles(GameRunner2.getUserMapsFolder()));
    rVal.addAll(safeListFiles(getDefaultMapsDir()));
    return rVal;
  }

  private static List<File> safeListFiles(final File f) {
    final File[] files = f.listFiles();
    if (files == null) {
      return Collections.emptyList();
    }
    return Arrays.asList(files);
  }


  private Set<NewGameChooserEntry> parseMapFiles() {
    List<File> allMapFiles = allMapFiles();
    final Set<NewGameChooserEntry> parsedMapSet = Sets.newHashSet();

    Collections.newSetFromMap(new ConcurrentHashMap(allMapFiles.size()));

    // Half the total number of cores being used as a generic sweet spot. @DanVanAtta found with 6 cores that 2 to 4 threads were best.
    final int halfCoreCount = (int) Math.ceil(Runtime.getRuntime().availableProcessors()/2);
    final ExecutorService threadPool = Executors.newFixedThreadPool(halfCoreCount);

    for (final File map : allMapFiles) {
      if (clearCacheMessenger.isCancelled()) {
        return ImmutableSet.of();
      }
      if (map.isDirectory()) {
        threadPool.submit(new Runnable() {
          @Override
          public void run() {
            parsedMapSet.addAll(populateFromDirectory(map));
          }
        });
      } else if (map.isFile() && map.getName().toLowerCase().endsWith(".zip")) {
        threadPool.submit(new Runnable() {
          @Override
          public void run() {
            parsedMapSet.addAll(populateFromZip(map));
          }
        });
      }
    }
    try {
      threadPool.shutdown();
      threadPool.awaitTermination(5,TimeUnit.MINUTES);
    } catch (InterruptedException e) {
      ClientLogger.logQuietly(e);
    }

    return parsedMapSet;
  }


  private static final List<NewGameChooserEntry> populateFromZip(final File map) {
    boolean badMapZip = false;
    final List<NewGameChooserEntry> entries = Lists.newArrayList();

    try (ZipFile zipFile = new ZipFile(map);
        final URLClassLoader loader = new URLClassLoader(new URL[] {map.toURI().toURL()})) {
      Enumeration<? extends ZipEntry> zipEntryEnumeration = zipFile.entries();
      while (zipEntryEnumeration.hasMoreElements()) {
        ZipEntry entry = zipEntryEnumeration.nextElement();
        if (entry.getName().startsWith("games/") && entry.getName().toLowerCase().endsWith(".xml")) {
          ZipProcessingResult result = processZipEntry(loader, entry, entries);
          if (result == ZipProcessingResult.ERROR) {
            badMapZip = true;
            break;
          }
        }
      }
    } catch (final IOException ioe) {
      ClientLogger.logQuietly(ioe);
    }

    if (badMapZip) {
      confirmWithUserAndThenDeleteCorruptZipFile(map);
    }
    return entries;
  }

  private static ZipProcessingResult processZipEntry(final URLClassLoader loader, final ZipEntry entry,
      final List<NewGameChooserEntry> entries) {
    final URL url = loader.getResource(entry.getName());
    if (url == null) {
      // not loading the URL means the XML is truncated or otherwise in bad shape
      return ZipProcessingResult.ERROR;
    }
    try {
      addNewGameChooserEntry(entries, new URI(url.toString().replace(" ", "%20")));
    } catch (final URISyntaxException e) {
      // only happens when URI couldn't be build and therefore no entry was added. That's fine ..
    }
    return ZipProcessingResult.SUCCESS;
  }

  /*
   * Open up a confirmation dialog, if user says yes, delete the map specified by
   * parameter, then show confirmation of deletion.
   */
  private static void confirmWithUserAndThenDeleteCorruptZipFile(final File map) {
    try {
      Runnable deleteMapRunnable = new Runnable() {
        @Override
        public void run() {
          final Component parentComponent = MainFrame.getInstance();
          String message = "Could not parse map file correctly, would you like to remove it?\n" + map.getAbsolutePath()
              + "\n(You may see this error message again if you keep the file)";
          String title = "Corrup Map File Found";
          final int optionType = JOptionPane.YES_NO_OPTION;
          int messageType = JOptionPane.WARNING_MESSAGE;
          final int result = JOptionPane.showConfirmDialog(parentComponent, message, title, optionType, messageType);
          if (result == JOptionPane.YES_OPTION) {
            final boolean deleted = map.delete();
            if (deleted) {
              messageType = JOptionPane.INFORMATION_MESSAGE;
              message = "File was deleted successfully.";
            } else if (!deleted && map.exists()) {
              message = "Unable to delete file, please remove it by hand:\n" + map.getAbsolutePath();
            }
            title = "File Removal Result";
            JOptionPane.showMessageDialog(parentComponent, message, title, messageType);
          }
        }
      };

      if( SwingUtilities.isEventDispatchThread() ) {
        deleteMapRunnable.run();
      } else {
        SwingUtilities.invokeAndWait(deleteMapRunnable);
      }
    } catch (final InvocationTargetException e) {
      ClientLogger.logError(e);
    } catch (final InterruptedException e) {
      ClientLogger.logQuietly(e);
    }
  }

  /**
   * @param entries
   *        list of entries where to add the new entry
   * @param uri
   *        URI of the new entry
   */
  private static void addNewGameChooserEntry(final List<NewGameChooserEntry> entries, final URI uri) {
    try {
      final NewGameChooserEntry newEntry = createEntry(uri);
      if (newEntry != null && !entries.contains(newEntry)) {
        entries.add(newEntry);
      }
    } catch (final EngineVersionException e) {
      System.out.println(e.getMessage());
    } catch (final SAXParseException e) {
      System.err
          .println("Could not parse:" + uri + " error at line:" + e.getLineNumber() + " column:" + e.getColumnNumber());
      e.printStackTrace();
    } catch (final Exception e) {
      System.err.println("Could not parse:" + uri);
      e.printStackTrace();
    }
  }

  public NewGameChooserEntry findByName(final String name) {
    for (int i = 0; i < size(); i++) {
      if (get(i).getGameData().getGameName().equals(name)) {
        return get(i);
      }
    }
    return null;
  }

  private static NewGameChooserEntry createEntry(final URI uri)
      throws IOException, GameParseException, SAXException, EngineVersionException {
    return new NewGameChooserEntry(uri);
  }

  private static List<NewGameChooserEntry> populateFromDirectory(final File mapDir) {
    final List<NewGameChooserEntry> entries = Lists.newArrayList();

    final File games = new File(mapDir, "games");
    if (!games.exists()) {
      // no games in this map dir
      return entries;
    }
    for (final File game : games.listFiles()) {
      if (game.isFile() && game.getName().toLowerCase().endsWith("xml")) {
        addNewGameChooserEntry(entries, game.toURI());
      }
    }
    return entries;
  }

  public boolean removeEntry(final NewGameChooserEntry entryToBeRemoved) {
    return this.removeElement(entryToBeRemoved);
  }
}
