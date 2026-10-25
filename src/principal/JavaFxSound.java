package principal;

import javafx.scene.media.AudioClip;

/**
 * The three clips of {@code /principal/sounds/}, played with JavaFX.
 * <p>
 * This class is the only bridge between the game and the audio toolkit: the
 * rules only see the {@link Sound} interface. A clip that cannot be loaded is
 * simply silent, so a machine without audio, or a missing file, never stops
 * the game.
 */
class JavaFxSound implements Sound {

    private final AudioClip drop  = load("drop.wav");
    private final AudioClip line  = load("linea.wav");
    private final AudioClip block = load("bloque.wav");

    @Override
    public void drop() {
        play(drop);
    }

    @Override
    public void line() {
        play(line);
    }

    @Override
    public void block() {
        play(block);
    }

    private static AudioClip load(String name) {
        try {
            var url = JavaFxSound.class.getResource("/principal/sounds/" + name);
            return url == null ? null : new AudioClip(url.toString());
        } catch (Throwable t) {
            System.out.println("Sound not available: " + name + " - " + t);
            return null;
        }
    }

    private static void play(AudioClip clip) {
        if (clip == null) {
            return;
        }
        try {
            clip.play();
        } catch (Throwable t) {
            // JavaFX audio may not be available in all environments
        }
    }
}
