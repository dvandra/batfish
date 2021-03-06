package org.batfish.question.specifiers;

import static com.google.common.base.MoreObjects.firstNonNull;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSortedSet;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.batfish.common.util.TracePruner;
import org.batfish.datamodel.HeaderSpace;
import org.batfish.datamodel.PacketHeaderConstraints;
import org.batfish.datamodel.PacketHeaderConstraintsUtil;
import org.batfish.datamodel.PathConstraints;
import org.batfish.datamodel.questions.Question;
import org.batfish.question.ReachabilityParameters;
import org.batfish.specifier.FlexibleInferFromLocationIpSpaceSpecifierFactory;
import org.batfish.specifier.FlexibleLocationSpecifierFactory;
import org.batfish.specifier.FlexibleNodeSpecifierFactory;
import org.batfish.specifier.FlexibleUniverseIpSpaceSpecifierFactory;
import org.batfish.specifier.IpSpaceSpecifier;
import org.batfish.specifier.IpSpaceSpecifierFactory;
import org.batfish.specifier.LocationSpecifier;
import org.batfish.specifier.LocationSpecifierFactory;
import org.batfish.specifier.NodeSpecifierFactory;

/**
 * A version of reachability question that supports {@link LocationSpecifier location} and {@link
 * IpSpaceSpecifier ipSpace} specifiers.
 */
public final class SpecifiersReachabilityQuestion extends Question {
  private static final String PROP_ACTIONS = "actions";
  private static final String PROP_HEADER_CONSTRAINT = "headers";
  private static final String PROP_IGNORE_FILTERS = "ignoreFilters";
  private static final String PROP_MAX_TRACES = "maxTraces";
  private static final String PROP_PATH_CONSTRAINT = "pathConstraints";

  private static final LocationSpecifierFactory LOCATION_SPECIFIER_FACTORY =
      LocationSpecifierFactory.load(FlexibleLocationSpecifierFactory.NAME);
  private static final NodeSpecifierFactory NODE_SPECIFIER_FACTORY =
      NodeSpecifierFactory.load(FlexibleNodeSpecifierFactory.NAME);

  @Nonnull private final DispositionSpecifier _actions;
  @Nonnull private final PacketHeaderConstraints _headerConstraints;
  private final boolean _ignoreFilters;
  private final int _maxTraces;
  @Nonnull private final PathConstraintsInput _pathConstraints;

  /**
   * Create a new reachability question. {@code null} values result in default parameter values.
   *
   * @param actions set of actions/flow dispositions to search for (default is {@code success})
   * @param headerConstraints header constraints that constrain the search space of valid flows.
   *     Default is unconstrained.
   * @param ignoreFilters whether to ignore ingress and egress ACLs.
   * @param pathConstraints path constraints dictating where a flow can originate/terminate/transit.
   *     Default is unconstrained.
   */
  @JsonCreator
  public SpecifiersReachabilityQuestion(
      @Nullable @JsonProperty(PROP_ACTIONS) DispositionSpecifier actions,
      @Nullable @JsonProperty(PROP_HEADER_CONSTRAINT) PacketHeaderConstraints headerConstraints,
      @Nullable @JsonProperty(PROP_IGNORE_FILTERS) Boolean ignoreFilters,
      @Nullable @JsonProperty(PROP_MAX_TRACES) Integer maxTraces,
      @Nullable @JsonProperty(PROP_PATH_CONSTRAINT) PathConstraintsInput pathConstraints) {
    _actions = firstNonNull(actions, DispositionSpecifier.SUCCESS_SPECIFIER);
    _headerConstraints = firstNonNull(headerConstraints, PacketHeaderConstraints.unconstrained());
    _ignoreFilters = firstNonNull(ignoreFilters, false);
    _maxTraces = firstNonNull(maxTraces, TracePruner.DEFAULT_MAX_TRACES);
    _pathConstraints = firstNonNull(pathConstraints, PathConstraintsInput.unconstrained());
  }

  SpecifiersReachabilityQuestion() {
    this(
        DispositionSpecifier.SUCCESS_SPECIFIER,
        PacketHeaderConstraints.unconstrained(),
        false,
        TracePruner.DEFAULT_MAX_TRACES,
        PathConstraintsInput.unconstrained());
  }

  @Nonnull
  @JsonProperty(PROP_ACTIONS)
  public DispositionSpecifier getActions() {
    return _actions;
  }

  @Nonnull
  @JsonProperty(PROP_HEADER_CONSTRAINT)
  public PacketHeaderConstraints getHeaderConstraints() {
    return _headerConstraints;
  }

  @JsonProperty(PROP_IGNORE_FILTERS)
  public boolean getIgnoreFilters() {
    return _ignoreFilters;
  }

  @JsonProperty(PROP_MAX_TRACES)
  public int getMaxTraces() {
    return _maxTraces;
  }

  @JsonProperty(PROP_PATH_CONSTRAINT)
  @Nonnull
  private PathConstraintsInput getPathConstraintsInput() {
    return _pathConstraints;
  }

  @Override
  public boolean getDataPlane() {
    return true;
  }

  @VisibleForTesting
  HeaderSpace getHeaderSpace() {
    return PacketHeaderConstraintsUtil.toHeaderSpaceBuilder(getHeaderConstraints()).build();
  }

  @VisibleForTesting
  PathConstraints getPathConstraints() {
    PathConstraints.Builder builder =
        PathConstraints.builder()
            .withStartLocation(
                LOCATION_SPECIFIER_FACTORY.buildLocationSpecifier(
                    _pathConstraints.getStartLocation()))
            .withEndLocation(
                NODE_SPECIFIER_FACTORY.buildNodeSpecifier(_pathConstraints.getEndLocation()));
    /*
     * Explicit check for null, because null expands into ALL nodes, which is usually not the
     * desired behavior for waypointing constraints
     */

    if (_pathConstraints.getTransitLocations() != null) {
      builder.through(
          NODE_SPECIFIER_FACTORY.buildNodeSpecifier(_pathConstraints.getTransitLocations()));
    }
    if (_pathConstraints.getForbiddenLocations() != null) {
      builder.avoid(
          NODE_SPECIFIER_FACTORY.buildNodeSpecifier(_pathConstraints.getForbiddenLocations()));
    }
    return builder.build();
  }

  @Override
  public String getName() {
    return "specifiersReachability";
  }

  private IpSpaceSpecifier getDestinationIpSpaceSpecifier() {
    return IpSpaceSpecifierFactory.load(FlexibleUniverseIpSpaceSpecifierFactory.NAME)
        .buildIpSpaceSpecifier(_headerConstraints.getDstIps());
  }

  private IpSpaceSpecifier getSourceIpSpaceSpecifier() {
    return IpSpaceSpecifierFactory.load(FlexibleInferFromLocationIpSpaceSpecifierFactory.NAME)
        .buildIpSpaceSpecifier(_headerConstraints.getSrcIps());
  }

  ReachabilityParameters getReachabilityParameters() {
    PathConstraints pathConstraints = getPathConstraints();

    return ReachabilityParameters.builder()
        .setActions(
            ImmutableSortedSet.copyOf(
                ReachabilityParameters.filterDispositions(getActions().getDispositions())))
        .setDestinationIpSpaceSpecifier(getDestinationIpSpaceSpecifier())
        .setFinalNodesSpecifier(pathConstraints.getEndLocation())
        .setForbiddenTransitNodesSpecifier(pathConstraints.getForbiddenLocations())
        .setHeaderSpace(getHeaderSpace())
        .setIgnoreFilters(getIgnoreFilters())
        .setRequiredTransitNodesSpecifier(pathConstraints.getTransitLocations())
        .setSourceLocationSpecifier(pathConstraints.getStartLocation())
        .setSourceIpSpaceSpecifier(getSourceIpSpaceSpecifier())
        .setSpecialize(true)
        .build();
  }

  @VisibleForTesting
  static Builder builder() {
    return new Builder();
  }

  @VisibleForTesting
  static final class Builder {
    private DispositionSpecifier _actions;
    private PacketHeaderConstraints _headerConstraints;
    private Integer _maxTraces;
    private PathConstraintsInput _pathConstraints;
    private Boolean _ignoreFilters;

    private Builder() {}

    public Builder setActions(DispositionSpecifier actions) {
      _actions = actions;
      return this;
    }

    public Builder setHeaderConstraints(PacketHeaderConstraints headerConstraints) {
      _headerConstraints = headerConstraints;
      return this;
    }

    public Builder setIgnoreFilters(boolean ignoreFilters) {
      _ignoreFilters = ignoreFilters;
      return this;
    }

    public Builder setMaxTraces(Integer maxTraces) {
      _maxTraces = maxTraces;
      return this;
    }

    public Builder setPathConstraints(PathConstraintsInput pathConstraintsInput) {
      _pathConstraints = pathConstraintsInput;
      return this;
    }

    public SpecifiersReachabilityQuestion build() {
      return new SpecifiersReachabilityQuestion(
          _actions, _headerConstraints, _ignoreFilters, _maxTraces, _pathConstraints);
    }
  }
}
