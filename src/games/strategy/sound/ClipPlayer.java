package games.strategy.sound;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.AudioFormat.Encoding;

import games.strategy.debug.ClientLogger;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.PlayerID;
import games.strategy.engine.framework.headlessGameServer.HeadlessGameServer;
import games.strategy.triplea.ResourceLoader;

/**
 * Utility for loading and playing sound clips.
 * Stores a preference in the user preferences for being silent.
 * The property will persist and be reloaded after the virtual machine
 * has been stopped and restarted.
 * <br>
 * <br>
 * <br>
 * <br>
 * <br>
 * How it works: <br>
 * <b>Sound.Default.Folder</b>=ww2 <br>
 * This is the "key" that tells the engine which sound folder to use as the DEFAULT sound folder. <br>
 * The default folders are as follows: <br>
 * "<b>ww2</b>" (which should cover ww1 - ww2 - ww3 sounds), <br>
 * "<b>preindustrial</b>" (anything from muskets/cannons (1500) to right before ww1 (1900), <br>
 * "<b>classical</b>" (the ancient era, anything before cannons became a mainstay (10,000 bce - 1500 ad) <br>
 * "<b>future</b>" (sci-fi, spaceships, lasers, etc) <br>
 * <br>
 * After this, you can specify specific sounds if you want, using the "sound key location" (aka: sound map folder). <br>
 * The sound key location is the exact folder name for a sound you want, located under the "generic" folder.
 * What I mean by this is that all sound key locations that triplea supports, are the names of all the folders in the
 * "assets/sounds/generic/" folder. <br>
 * example: <br>
 * <b>battle_aa_miss</b>=ww2/battle_aa_miss;future/battle_aa_miss/battle_aa_miss_01_ufo_flyby.wav <br>
 * "battle_aa_miss" is one of the folders under "generic", therefore it is a "sound location key" <br>
 * We can set this equal to any list of sounds paths, each separated by a semicolon (;). The engine will pick one at
 * random each time we
 * need to play this sound. <br>
 * The "sound path" can be a "folder" or a "file". If it is a folder, we will use all the sounds in that folder.
 * If it is a file, we will only use that file. We can use a file and folder and another file and another folder, all
 * together. <br>
 * Example: "<b>ww2/battle_aa_miss</b>" is the sound path for a folder, so we will use all the sounds in that folder.
 * "<b>future/battle_aa_miss/battle_aa_miss_01_ufo_flyby.wav</b>" is a specific file, so we will use just this file.
 * Because we use both of these together, the engine will make a list of all the files in that folder, plus that single
 * file we specified,
 * then it will randomly pick one of this whole list every time it needs to play the "battle_aa_miss" sound. <br>
 * <br>
 * So, lets say that you want to play 2 sounds, for the "battle_land" sound key.
 * One of them is located at "tripleainstallfolder/assets/sounds/generic/battle_land_01_angry_drumming_noise.wav".
 * The other is located at "tripleainstallfolder/assets/sounds/classical/battle_land_02_war_trumpets.wav". Then the
 * entry would look like
 * this: <br>
 * battle_land=generic/battle_land_01_angry_drumming_noise.wav;classical/battle_land_02_war_trumpets.wav <br>
 * If you wanted it to also play every single sound in the "tripleainstallfolder/assets/sounds/ww2/battle_land/" folder,
 * then you would add
 * that folder to path: <br>
 * battle_land=generic/battle_land_01_angry_drumming_noise.wav;classical/battle_land_02_war_trumpets.wav;ww2/battle_land
 * <br>
 * <br>
 * Furthermore, we can customize the sound key by adding "_nationName" onto the end of it. So if you want a specific
 * sound for a german land
 * attack, then use: <br>
 * battle_land<b>_Germans</b>=misc/battle_land/battle_land_Germans_panzers_and_yelling_in_german.wav <br>
 * You can use nation specific sound keys for almost all sounds, though things like game_start, or chat_message, will
 * never use them. <br>
 * <br>
 * <br>
 * <br>
 * <br>
 * <b>You do not need to specify every single "sound key". This is why/because we have the "Sound.Default.Folder".</b>
 * <br>
 * <br>
 * The logic is as follows: <br>
 * Engine needs to play the "game_start" sound. <br>
 * 1. Check for a sound.properties file. <br>
 * 2. If none exists, pretend that one exists and that it only contains this line: "Sound.Default.Folder=ww2" <br>
 * 3. Look in the sound.properties file for the specific sound key "game_start" <br>
 * 4. Create a list of all sounds that the key includes.
 * If no key, then just use all the sounds in "Sound.Default.Folder/sound_key/" (which for us would be "ww2/game_start/"
 * folder). <br>
 * 5. If no sounds are found, then use all the sounds located at "generic/sound_key/" (which for us would be
 * "generic/game_start").
 * (if any sounds are found in step 4 above, then we ignore the generic folder completely) <br>
 * 6. Randomize the list's order, then pick one, and play the sound.
 */
public class ClipPlayer {
  protected static final String ASSETS_SOUNDS_FOLDER = "sounds";
  private static final String SOUND_PREFERENCE_GLOBAL_SWITCH = "beSilent2";
  private static final String SOUND_PREFERENCE_PREFIX = "sound_";
  private static final boolean DEFAULT_SOUND_SILENCED_SWITCH_SETTING = false;

  protected final Map<String, List<URL>> sounds = new HashMap<String, List<URL>>();
  private final Set<String> mutedClips = new HashSet<String>();
  private final Set<String> subFolders = new HashSet<String>();
  private final ClipCache clipCache = new ClipCache();
  private boolean beSilent = false;
  private final ResourceLoader resourceLoader;
  private static ClipPlayer clipPlayer;

  public static synchronized ClipPlayer getInstance() {
    if (clipPlayer == null) {
      clipPlayer = new ClipPlayer(ResourceLoader.getMapResourceLoader(null, true));
      SoundPath.preLoadSounds(SoundPath.SoundType.GENERAL);
    }
    return clipPlayer;
  }

  public static synchronized ClipPlayer getInstance(final ResourceLoader resourceLoader, final GameData data) {
    // make a new clip player if we switch resource loaders (ie: if we switch maps)
    if (clipPlayer == null || clipPlayer.resourceLoader != resourceLoader) {
      // stop and close any playing clips
      if (clipPlayer != null) {
        clipPlayer.clipCache.removeAll();
      }
      // make a new clip player with our new resource loader
      clipPlayer = new ClipPlayer(resourceLoader, data);
      SoundPath.preLoadSounds(SoundPath.SoundType.GENERAL);
    }
    return clipPlayer;
  }

  private ClipPlayer(final ResourceLoader resourceLoader) {
    this.resourceLoader = resourceLoader;
    final Preferences prefs = Preferences.userNodeForPackage(ClipPlayer.class);
    beSilent = Boolean.parseBoolean(System.getProperty(HeadlessGameServer.TRIPLEA_HEADLESS, "false"))
        || prefs.getBoolean(SOUND_PREFERENCE_GLOBAL_SWITCH, DEFAULT_SOUND_SILENCED_SWITCH_SETTING);
    final HashSet<String> choices = SoundPath.getAllSoundOptions();

    for (final String sound : choices) {
      final boolean muted = prefs.getBoolean(SOUND_PREFERENCE_PREFIX + sound, false);
      if (muted) {
        mutedClips.add(sound);
      }
    }
  }

  private ClipPlayer(final ResourceLoader resourceLoader, final GameData data) {
    this(resourceLoader);
    for (final PlayerID p : data.getPlayerList().getPlayers()) {
      subFolders.add(p.getName());
    }
  }

  /**
   * If set to true, no sounds will play.
   * This property is persisted using the java.util.prefs API, and will persist after the vm has stopped.
   *
   * @param aBool new value for m_beSilent
   */
  protected static void setBeSilent(final boolean aBool) {
    final ClipPlayer clipPlayer = getInstance();
    clipPlayer.beSilent = aBool;
    setBeSilentInPreferencesWithoutAffectingCurrent(aBool);
  }

  public static void setBeSilentInPreferencesWithoutAffectingCurrent(final boolean silentBool) {
    final Preferences prefs = Preferences.userNodeForPackage(ClipPlayer.class);
    final boolean current = prefs.getBoolean(SOUND_PREFERENCE_GLOBAL_SWITCH, DEFAULT_SOUND_SILENCED_SWITCH_SETTING);
    boolean setPref = silentBool != current;
    if (!setPref) {
      try {
        setPref = !Arrays.asList(prefs.keys()).contains(SOUND_PREFERENCE_GLOBAL_SWITCH);
      } catch (final BackingStoreException e) {
        ClientLogger.logQuietly(e);
      }
    }
    if (setPref) {
      prefs.putBoolean(SOUND_PREFERENCE_GLOBAL_SWITCH, silentBool);
      try {
        prefs.flush();
      } catch (final BackingStoreException e) {
        ClientLogger.logQuietly(e);
      }
    }
  }

  protected static boolean getBeSilent() {
    final ClipPlayer clipPlayer = getInstance();
    return clipPlayer.beSilent;
  }


  protected boolean isMuted(final String clipName) {
    if (mutedClips.contains(clipName)) {
      return true;
    }
    if (!SoundPath.getAllSoundOptions().contains(clipName)) {
      // for custom sound clips, with custom paths, silence based on more similar sound clip settings
      if (clipName.startsWith(SoundPath.CLIP_BATTLE_X_PREFIX) && clipName.endsWith(SoundPath.CLIP_BATTLE_X_HIT)) {
        return mutedClips.contains(SoundPath.CLIP_BATTLE_AA_HIT);
      }
      if (clipName.startsWith(SoundPath.CLIP_BATTLE_X_PREFIX) && clipName.endsWith(SoundPath.CLIP_BATTLE_X_MISS)) {
        return mutedClips.contains(SoundPath.CLIP_BATTLE_AA_MISS);
      }
      if (clipName.startsWith(SoundPath.CLIP_TRIGGERED_NOTIFICATION_SOUND)) {
        return mutedClips.contains(SoundPath.CLIP_TRIGGERED_NOTIFICATION_SOUND);
      }
      if (clipName.startsWith(SoundPath.CLIP_TRIGGERED_DEFEAT_SOUND)) {
        return mutedClips.contains(SoundPath.CLIP_TRIGGERED_DEFEAT_SOUND);
      }
      if (clipName.startsWith(SoundPath.CLIP_TRIGGERED_VICTORY_SOUND)) {
        return mutedClips.contains(SoundPath.CLIP_TRIGGERED_VICTORY_SOUND);
      }
    }
    return false;
  }

  protected void setMute(final String clipName, final boolean value) {
    // we want to avoid unnecessary calls to preferences
    final boolean isCurrentCorrect = mutedClips.contains(clipName) == value;
    if (isCurrentCorrect) {
      return;
    }
    if (value == true) {
      mutedClips.add(clipName);
    } else {
      mutedClips.remove(clipName);
    }
    final Preferences prefs = Preferences.userNodeForPackage(ClipPlayer.class);
    prefs.putBoolean(SOUND_PREFERENCE_PREFIX + clipName, value);
  }


  /** Flushes sounds preferences to persisted data store. This method is *slow* and resource expensive. */
  protected void saveSoundPreferences() {
    final Preferences prefs = Preferences.userNodeForPackage(ClipPlayer.class);
    try {
      prefs.flush();
    } catch (final BackingStoreException e) {
      ClientLogger.logQuietly(e);
    }
  }

  public static void play(final String clipName) {
    play(clipName, null);
  }

  /**
   * @param clipName - the file name of the clip
   * @param playerId - the name of the player, or null
   */
  public static void play(String clipPath, PlayerID playerId) {
    getInstance().playClip(clipPath, playerId);
  }


  private void playClip(final String clipName, final PlayerID playerId) {
    if (beSilent || isMuted(clipName)) {
      return;
    }
    // run in a new thread, so that we do not delay the game
    final Runnable loadSounds = new Runnable() {
      @Override
      public void run() {
        try {
          String subFolder = null;
          if (playerId != null) {
            subFolder = playerId.getName();
          }

          final Clip clip = loadClip(clipName, subFolder, false);
          if (clip != null) {
            clip.setFramePosition(0);
            clip.loop(0);
          }
        } catch (final Exception e) {
          ClientLogger.logQuietly(e);
        }
      }
    };
    (new Thread(loadSounds, "Triplea sound loader for " + clipName)).start();
  }

  /**
   * To reduce the delay when the clip is first played, we can preload clips here.
   *
   * @param clipName name of the clip
   */
  protected void preLoadClip(final String clipName) {
    loadClip(clipName, null, true);
    for (final String sub : subFolders) {
      loadClip(clipName, sub, true);
    }
  }

  private synchronized Clip loadClip(final String clipName, final String subFolder, final boolean parseThenTestOnly) {
    if (beSilent || isMuted(clipName)) {
      return null;
    }
    try {
      if (subFolder != null && subFolder.length() > 0) {
        final Clip clip = loadClipPath(clipName + "_" + subFolder, true, parseThenTestOnly);
        if (clip != null) {
          return clip;
        }
        // if null, there is no sub folder, so check for a non-sub-folder sound.
        return loadClipPath(clipName, false, parseThenTestOnly);
      } else {
        return loadClipPath(clipName, false, parseThenTestOnly);
      }
    } catch (final Exception e) {
      ClientLogger.logQuietly(e);
    }
    return null;
  }

  private Clip loadClipPath(final String pathName, final boolean subFolder, final boolean parseThenTestOnly) {
    if (!sounds.containsKey(pathName)) {
      // parse sounds for the first time
      parseClipPaths(pathName, subFolder);
    }
    final List<URL> availableSounds = sounds.get(pathName);
    if (parseThenTestOnly || availableSounds == null || availableSounds.isEmpty()) {
      return null;
    }
    // we want to pick a random sound from this folder, as users don't like hearing the same ones over
    // and over again
    Collections.shuffle(availableSounds);
    final URL clipFile = availableSounds.get(0);
    return clipCache.get(clipFile);
  }

  /**
   * The user may or may not have a sounds.properties file. If they do not, we should have a default folder (ww2) that
   * we use for sounds.
   * Because we do not want a lot of duplicate sound files, we also have a "generic" sound folder.
   * If a sound can not be found for a soundpath using the sounds.properties or default folder, then we try to find one
   * in the generic
   * folder.
   * The sounds.properties file can specify all the sounds to use for a specific sound path (multiple per path).
   * If there is no key for that path, we try by the default way. <br>
   * <br>
   * Example sounds.properties keys:<br>
   * Sound.Default.Folder=ww2<br>
   * battle_aa_miss=ww2/battle_aa_miss/battle_aa_miss_01_aa_artillery_and_flyby.wav;ww2/battle_aa_miss/
   * battle_aa_miss_02_just_aa_artillery.
   * wav<br>
   * phase_purchase_Germans=phase_purchase_Germans/game_start_Germans_01_anthem.wav
   *
   * @param pathName
   * @param subFolder
   */
  private void parseClipPaths(final String pathName, final boolean subFolder) {
    String resourcePath = SoundProperties.getInstance(resourceLoader).getProperty(pathName);
    if (resourcePath == null) {
      resourcePath = SoundProperties.getInstance(resourceLoader).getDefaultEraFolder() + "/" + pathName;
    }
    resourcePath = resourcePath.replace('\\', '/');
    final List<URL> availableSounds = new ArrayList<URL>();
    if ("NONE".equals(resourcePath)) {
      sounds.put(pathName, availableSounds);
      return;
    }
    for (final String path : resourcePath.split(";")) {
      availableSounds.addAll(createAndAddClips(ASSETS_SOUNDS_FOLDER + "/" + path));
    }
    if (availableSounds.isEmpty()) {
      final String genericPath = SoundProperties.GENERIC_FOLDER + "/" + pathName;
      availableSounds.addAll(createAndAddClips(ASSETS_SOUNDS_FOLDER + "/" + genericPath));
    }
    sounds.put(pathName, availableSounds);
  }

  /**
   * @param resourceAndPathURL
   *        (URL uses '/', not File.separator or '\')
   */
  protected List<URL> createAndAddClips(final String resourceAndPathURL) {
    final List<URL> availableSounds = new ArrayList<URL>();
    final URL thisSoundURL = resourceLoader.getResource(resourceAndPathURL);
    if (thisSoundURL == null) {
      return availableSounds;
    }
    URI thisSoundURI;
    File thisSoundFile;
    // we are checking to see if this is a file, to see if it is a directory, or a sound, or a zipped directory, or a
    // zipped sound. There
    // might be a better way to do this...
    try {
      thisSoundURI = thisSoundURL.toURI();
      try {
        thisSoundFile = new File(thisSoundURI);
      } catch (final Exception e) {
        try {
          thisSoundFile = new File(thisSoundURI.getPath());
        } catch (final Exception e3) {
          thisSoundFile = new File(thisSoundURL.getPath());
        }
      }
    } catch (final URISyntaxException e1) {
      try {
        thisSoundFile = new File(thisSoundURL.getPath());
      } catch (final Exception e4) {
        thisSoundFile = null;
      }
    } catch (final Exception e2) {
      thisSoundFile = null;
    }

    if (thisSoundFile.isDirectory()) {
      for (final File sound : thisSoundFile.listFiles()) {
        if (isSoundFileNamed(sound)) {
          try {
            final URL individualSoundURL = sound.toURI().toURL();
            if (testClipSuccessful(individualSoundURL)) {
              availableSounds.add(individualSoundURL);
            }
          } catch (final MalformedURLException e) {
            String msg = "Error " + e.getMessage() + " with sound file: " + sound.getPath();
            ClientLogger.logQuietly(msg, e);
          }
        }
      }
    } else {
      if (!isSoundFileNamed(thisSoundFile)) {
        return availableSounds;
      }
      if (testClipSuccessful(thisSoundURL)) {
        availableSounds.add(thisSoundURL);
      }
    }
    return availableSounds;
  }

  private static boolean isSoundFileNamed(final File soundFile) {
    return soundFile.getName().endsWith(".wav") || soundFile.getName().endsWith(".au")
        || soundFile.getName().endsWith(".aiff")
        || soundFile.getName().endsWith(".midi") || soundFile.getName().endsWith(".mp3");
  }


  protected static Clip createClip(final URL clipFile) {
    try {
      final AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(clipFile);
      final AudioFormat audioFormat = audioInputStream.getFormat();
      final AudioFormat decodedFormat = decodeFormat(audioFormat);
      final DataLine.Info info = new DataLine.Info(Clip.class, decodedFormat);
      final Clip clip = (Clip) AudioSystem.getLine(info);
      final AudioInputStream audioStream2 = AudioSystem.getAudioInputStream(decodedFormat, audioInputStream);
      clip.open(audioStream2);
      return clip;
    } catch (final Exception e) {
      ClientLogger.logQuietly("failed to create clip: " + clipFile.toString(), e);
      return null;
    }
  }

  private static AudioFormat decodeFormat(AudioFormat format) throws LineUnavailableException {
    final float sampleRate = format.getSampleRate();
    final int sampleSizeInBits = format.getSampleSizeInBits();
    final int channelCount = format.getChannels();
    final int frameSize = format.getFrameSize();
    final boolean bigEndian = format.isBigEndian();
    return new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, sampleRate, sampleSizeInBits, channelCount, frameSize,
        format.getSampleRate(), bigEndian);
  }

  private static synchronized boolean testClipSuccessful(final URL clipFile) {
    Clip clip = createClip(clipFile);
    return clip != null;
  }
}
