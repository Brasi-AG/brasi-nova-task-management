package de.brasi.nova.taskmanagement.entity.dictionary.code;

import java.util.Map;

/**
 * Deterministisches Mapping der fuenf kanonischen {@code DIC_TAG_SEVERITY}-CODEs auf ihren
 * jeweiligen UI-Kit-Hex-Farbwert (Intake #135, Phase 1).
 *
 * <p>Quelle der Werte: nova-ui-kit {@code src/_variables.scss} @2152d89 (Tailwind-Palette).
 * Repraesentativer Ton = chip-Modus-Hintergrund {@code --*-400}; {@code SECONDARY} nutzt
 * {@code --surface-300}. Der tag-Modus nutzt abweichende Token-Paare ({@code --*-100}/{@code
 * --*-700}); ein Einzel-Hex reproduziert die bisherige Darstellung daher NICHT exakt - die einmalige
 * Verschiebung ist owner-akzeptiert (SPEC/Options-Gate). {@code PRIMARY} folgt dem Default-Theme
 * (blue); ein abweichendes App-Theme bleibt ein dokumentierter Rest.
 *
 * <p>Diese Konstanten sind die EINE Wahrheit fuer den severity-&gt;Hex-Backfill: der V8-Backfill-SQL
 * verwendet exakt dieselben Hex-Werte (CASE auf CODE), und der Backend-Schreibpfad (Phase-1-Slice 2)
 * leitet {@code COLOR} aus der severity ueber genau dieses Mapping ab. Nicht instanziierbar.
 */
public final class TagSeverityColors {

  private TagSeverityColors() {}

  /** blue-400 (THEME-DEFAULT; primary ist theme-abhaengig blue/green/violet/pink). */
  public static final String PRIMARY = "#60A5FA";

  /** green-400. */
  public static final String SUCCESS = "#4ADE80";

  /** amber-400. */
  public static final String WARN = "#FBBF24";

  /** red-400. */
  public static final String DANGER = "#F87171";

  /** surface-300. */
  public static final String SECONDARY = "#CBD5E1";

  /**
   * Unveraenderliche CODE-&gt;Hex-Zuordnung ueber alle fuenf kanonischen Severity-CODEs. Die
   * Schluesselmenge ist exakt die kanonische Domaene aus {@link TagSeverityCodes}.
   */
  public static final Map<String, String> BY_CODE =
      Map.of(
          TagSeverityCodes.PRIMARY, PRIMARY,
          TagSeverityCodes.SUCCESS, SUCCESS,
          TagSeverityCodes.WARN, WARN,
          TagSeverityCodes.DANGER, DANGER,
          TagSeverityCodes.SECONDARY, SECONDARY);
}
