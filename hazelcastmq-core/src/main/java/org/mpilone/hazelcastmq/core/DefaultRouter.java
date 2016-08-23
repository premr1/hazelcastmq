package org.mpilone.hazelcastmq.core;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.mpilone.hazelcastmq.core.DefaultRouterContext.RouterData;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IMap;
import com.hazelcast.map.AbstractEntryProcessor;

/**
 *
 * @author mpilone
 */
class DefaultRouter implements Router {

  private final BrokerConfig config;
  private final HazelcastInstance hazelcastInstance;
  private final TrackingParent<Router> parent;
  private final DataStructureKey channelKey;

  private volatile boolean closed;

  DefaultRouter(DataStructureKey channelKey, TrackingParent<Router> parent,
      BrokerConfig config) {
    this.config = config;
    this.channelKey = channelKey;
    this.hazelcastInstance = config.getHazelcastInstance();
    this.parent = parent;

    // Make sure the router data exists for this router in the data map.
    getRouterDataMap().putIfAbsent(channelKey,    new RouterData(channelKey));
  }

  @Override
  public void close() {
    closed = true;

    parent.remove(this);
  }

  @Override
  public boolean isClosed() {
    return closed;
  }

  private IMap<DataStructureKey, RouterData> getRouterDataMap() {
    return hazelcastInstance.getMap(DefaultRouterContext.ROUTER_DATA_MAP_NAME);
  }

  @Override
  public void addRoute(DataStructureKey targetKey, String... routingKeys) {
    requireNotClosed();

    getRouterDataMap().executeOnKey(channelKey, new AddRouteProcessor(
        targetKey, routingKeys));
  }

  @Override
  public void removeRoute(DataStructureKey targetKey, String... routingKeys) {
    requireNotClosed();

    getRouterDataMap().executeOnKey(channelKey,
        new RemoveRouteProcessor(targetKey, routingKeys));
  }

  @Override
  public DataStructureKey getChannelKey() {
    return channelKey;
  }

  @Override
  public Collection<Route> getRoutes() {
    requireNotClosed();

    // The route list in RouterData is already unmodifiable.
    return getRouterDataMap().get(channelKey).getRoutes();
  }

  @Override
  public RoutingStrategy getRoutingStrategy() {
    requireNotClosed();

    return getRouterDataMap().get(channelKey).getRoutingStrategy();
  }

  @Override
  public void setRoutingStrategy(RoutingStrategy strategy) {
    requireNotClosed();

    getRouterDataMap().executeOnKey(channelKey,
        new SetRoutingStrategyProcessor(strategy));
  }

  /**
   * Checks if the router is closed and throws an exception if it is.
   *
   * @throws HazelcastMQException if the context is closed
   */
  private void requireNotClosed() throws HazelcastMQException {
    if (closed) {
      throw new HazelcastMQException("Router is closed.");
    }
  }

  public void routeMessages() {

    // Get the strategy and possible output routes.
    final RouterData routerData = getRouterDataMap().get(channelKey);
    final RoutingStrategy strategy = routerData.getRoutingStrategy();
    final Collection<Route> routes = routerData.getRoutes();

    // Create a channel context to access input and output channels.
    try (DefaultChannelContext channelContext = new DefaultChannelContext(
        child -> {
        }, config)) {

      // Create the input channel to read from.
      try (Channel sourceChannel = channelContext.createChannel(routerData.
          getChannelKey())) {

        // As long as we have messages, keep routing.
        Message<?> msg;
        while ((msg = sourceChannel.receive(0, TimeUnit.SECONDS)) != null) {

          // Use the strategy to route the message and send it to each target channel.
          final Message<?> _msg = msg;
          strategy.routeMessage(_msg, routes).stream().forEach(targetKey -> {

            try (Channel targetChannel = channelContext.createChannel(targetKey)) {
              targetChannel.send(_msg, 0, TimeUnit.SECONDS);
            }
          });
        }
      }
    }
  }

  private static class AddRouteProcessor extends AbstractEntryProcessor<DataStructureKey, RouterData> {

    private final DataStructureKey targetKey;
    private final String[] routingKeys;

    public AddRouteProcessor(DataStructureKey targetKey, String[] routingKeys) {
      super(true);

      this.targetKey = targetKey;
      this.routingKeys = routingKeys;
    }

    @Override
    public Object process(Map.Entry<DataStructureKey, RouterData> entry) {

      final RouterData data = entry.getValue();

      if (data == null) {
        return null;
      }

      final Map<DataStructureKey, Route> routeMap = data.getRoutes().stream().
          collect(Collectors.toMap(Route::getChannelKey, r -> r));
      final boolean routeExists = routeMap.containsKey(targetKey);
      final Set<String> newRoutingKeys = routeExists ? new HashSet(routeMap.get(
          targetKey).getRoutingKeys()) : new HashSet<>();

      if (routingKeys == null || routingKeys.length == 0) {
        // Add the default routing key.
        newRoutingKeys.add(DEFAULT_ROUTING_KEY);
      }
      else {
        // Add all the routing keys given.
        newRoutingKeys.addAll(Arrays.asList(routingKeys));
      }

      // Put the entry into the map.
      routeMap.put(targetKey, new Route(targetKey, newRoutingKeys));

      RouterData newData = new RouterData(data.getChannelKey(), data.
          getRoutingStrategy(), routeMap.values());
      entry.setValue(newData);

      return newData;

    }
  }

  private static class RemoveRouteProcessor extends AbstractEntryProcessor<DataStructureKey, RouterData> {

    private final DataStructureKey targetKey;
    private final String[] routingKeys;

    public RemoveRouteProcessor(DataStructureKey targetKey, String[] routingKeys) {
      super(true);

      this.targetKey = targetKey;
      this.routingKeys = routingKeys;
    }

    @Override
    public Object process(Map.Entry<DataStructureKey, RouterData> entry) {

      final RouterData data = entry.getValue();

      if (data == null) {
        return null;
      }

      final Map<DataStructureKey, Route> routeMap = data.getRoutes().stream().
          collect(Collectors.toMap(Route::getChannelKey, r -> r));
      final boolean routeExists = routeMap.containsKey(targetKey);
      final Set<String> newRoutingKeys = routeExists ? new HashSet(routeMap.get(
          targetKey).getRoutingKeys()) : new HashSet<>();

      if (routingKeys == null || routingKeys.length == 0) {
        // Remove all routing keys.
        newRoutingKeys.clear();
      }
      else {
        // Remove the specific routing keys.
        newRoutingKeys.removeAll(Arrays.asList(routingKeys));
      }

      if (newRoutingKeys.isEmpty()) {
        // Remove the route if all routing keys are gone.
        routeMap.remove(targetKey);
      }
      else {
        // Update the route with the new routing keys.
        routeMap.put(targetKey, new Route(targetKey, newRoutingKeys));
      }

      RouterData newData = new RouterData(data.getChannelKey(), data.
          getRoutingStrategy(), routeMap.values());
      entry.setValue(newData);

      return newData;

    }

  }

  private static class SetRoutingStrategyProcessor extends
      AbstractEntryProcessor<DataStructureKey, RouterData> {

    private final RoutingStrategy strategy;

    public SetRoutingStrategyProcessor(RoutingStrategy strategy) {
      super(true);

      this.strategy = strategy;
    }

    @Override
    public Object process(Map.Entry<DataStructureKey, RouterData> entry) {
      final RouterData data = entry.getValue();

      if (data == null) {
        return null;
      }

      RouterData newData = new RouterData(data.getChannelKey(), strategy,
          data.getRoutes());
      entry.setValue(newData);

      return newData;
    }

  }

}