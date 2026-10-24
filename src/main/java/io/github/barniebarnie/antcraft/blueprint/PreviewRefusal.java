package io.github.barniebarnie.antcraft.blueprint;

import io.github.barniebarnie.antcraft.colony.BlueprintPlacement;
import io.github.barniebarnie.antcraft.colony.SiteRules;
import java.util.Optional;
import java.util.function.BiFunction;
import org.jspecify.annotations.Nullable;

/**
 * Why the server would refuse the previewed ghost ({@link BlueprintGhost#refusal}), worked out only when the placement
 * changes: the ghost is moved, turned or mirrored, the blueprint changes, or the colony's rules do. The preview asks every
 * frame with a ghost made anew, so the last ghost and rules are compared, the blueprint by identity first.
 */
public final class PreviewRefusal {
	private final BiFunction<BlueprintGhost, Optional<SiteRules>, Optional<BlueprintPlacement.Refusal>> check;
	private @Nullable BlueprintGhost ghost;
	private @Nullable Optional<SiteRules> rules;
	private Optional<BlueprintPlacement.Refusal> refusal = Optional.empty();

	public PreviewRefusal() {
		this(BlueprintGhost::refusal);
	}

	/** Works the refusal out with {@code check} rather than {@link BlueprintGhost#refusal}, for tests. */
	PreviewRefusal(BiFunction<BlueprintGhost, Optional<SiteRules>, Optional<BlueprintPlacement.Refusal>> check) {
		this.check = check;
	}

	/** Why {@code ghost} would be refused under {@code rules}, worked out again only if either changed since the last call. */
	public Optional<BlueprintPlacement.Refusal> get(BlueprintGhost ghost, Optional<SiteRules> rules) {
		if (this.ghost == null || this.rules == null || !samePlacement(this.ghost, ghost) || !sameRules(this.rules, rules)) {
			this.refusal = this.check.apply(ghost, rules);
			this.ghost = ghost;
			this.rules = rules;
		}
		return this.refusal;
	}

	/** Forgets the last placement, so the next one is worked out anew. */
	public void reset() {
		this.ghost = null;
		this.rules = null;
		this.refusal = Optional.empty();
	}

	private static boolean samePlacement(BlueprintGhost last, BlueprintGhost ghost) {
		return last.at().equals(ghost.at()) && last.rotation() == ghost.rotation() && last.mirror() == ghost.mirror()
			&& (last.blueprint() == ghost.blueprint() || last.blueprint().equals(ghost.blueprint()));
	}

	private static boolean sameRules(Optional<SiteRules> last, Optional<SiteRules> rules) {
		return last == rules || last.equals(rules);
	}
}
