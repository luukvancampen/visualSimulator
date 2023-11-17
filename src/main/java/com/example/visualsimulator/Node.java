package com.example.visualsimulator;

import javafx.application.Platform;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

public class Node implements Runnable {
    // Network.
    String id;
    double[] coordinate;
    double range;
    Network network;

    // Routing.
    private Map<String, List<RouteCacheEntry>> routeCache;
    private ArrayDeque<SendBufferEntry> sendBuffer;
    private Map<String, RouteRequestTableEntry> routeRequestTable;
    private int routeRequestIdentificationCounter;
    private List<GratituousReplyTableEntry> gratuitousReplyTable;
    private Map<String, List<NetworkLayerPacket>> maintenanceBuffer;

    Node(String id, double[] coordinate, double range, Network network) {
        this.id = id;
        this.coordinate = coordinate;
        this.range = range;
        this.network = network;

        network.nodes.put(id, this);

        reset();
    }

    void reset() {
        routeCache = new HashMap<>();
        sendBuffer = new ArrayDeque<>();
        routeRequestTable = new HashMap<>();
        routeRequestIdentificationCounter = 0;
        gratuitousReplyTable = new ArrayList<>();
        maintenanceBuffer = new HashMap<>();
    }

    @Override
    public void run() {
        while (true) {
            try {
                Thread.sleep(5);

                Optional<NetworkLayerPacket> maybePacket = this.receiveDSR();

                if (maybePacket.isPresent()) {
                    System.out.println();
                    System.out.println("### PACKET RECEIVED AT " + id + ": " + maybePacket.get().data);
                    System.out.println();
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /*
     * 
     * MAC.
     * 
     */

    public Optional<NetworkLayerPacket> receiveMACAW() {
        Optional<NetworkLayerPacket> maybePacket = network.receive(this); // TODO replace this with macaw.

        if (!maybePacket.isPresent()) {
            return Optional.empty();
        }

        NetworkLayerPacket packet = maybePacket.get();

        if (!packet.macDestination.equals(id) && !packet.macDestination.equals("ff:ff:ff:ff:ff:ff")) {
            return Optional.empty();
        }

        return Optional.of(packet);
    }

    public void sendMACAW(NetworkLayerPacket packet, Consumer<Boolean> callback) {
        network.send(packet); // replace this with macaw.

        // TODO route through macaw here.
        // Call the callback with `true` when successfully received an ack.
        // Else if we timed out, call the callback with `false`

        callback.accept(true);
    }

    // Helper function.
    public void sendMACAW(NetworkLayerPacket packet) {
        sendMACAW(packet, success -> {
        });
    }

    /*
     * 
     * Routing.
     * 
     */

    // Process a packet using DSR.
    // Returns packets with data in them if any.
    public Optional<NetworkLayerPacket> receiveDSR() {
        Optional<NetworkLayerPacket> maybePacket = receiveMACAW();

        if (!maybePacket.isPresent()) {
            return Optional.empty();
        }

        return receivePacket(maybePacket.get());
    }

    // Send data to a receiver using DSR.
    public void sendDSR(String receiver, String data) {
        NetworkLayerPacket packet = new NetworkLayerPacket();

        packet.ipSource = id;
        packet.ipDestination = receiver;

        packet.optionTypes = Set.of();
        packet.data = data;

        originatePacket(packet, false);
    }

    // Originate a packet.
    // This function will broadcast it if the destination address is
    // 255.255.255.255.
    // Else, if a SourceRoute option in included in the packet, it will forward it
    // over that route.
    // Else, we try to find a route in our routeCache.
    // If the route is in the cache, include a SourceRoute option and send to over
    // that route.
    // Else if we know no route, store the packet in the sendBuffer and perform
    // route discovery.
    private void originatePacket(NetworkLayerPacket packet, boolean piggyBackRouteRequest) {
        if (packet.ipDestination.equals("255.255.255.255")) {
            sendMACAW(packet);
        } else if (packet.optionTypes.contains(OptionType.SourceRoute)) {
            sendWithMaintenance(packet);
        } else {
            Optional<List<String>> maybeRoute = findRoute(packet.ipDestination);

            if (maybeRoute.isPresent()) {
                List<String> sourceRoute = maybeRoute.get();
                sendWithSourceRoute(packet, sourceRoute);
            } else {
                routeDiscovery(packet, piggyBackRouteRequest);
            }
        }
    }

    // Send the packet over the given sourceRoute.
    // Will include a SourceRoute option.
    private void sendWithSourceRoute(NetworkLayerPacket packet, List<String> sourceRoute) {
        packet.sourceCoordinate = coordinate;
        packet.received = new HashSet<>();

        packet.macSource = id;

        packet.timeToLive = 255;

        if (!sourceRoute.isEmpty()) {
            Set<OptionType> optionTypes = new HashSet<>();
            optionTypes.addAll(packet.optionTypes);
            optionTypes.add(OptionType.SourceRoute);

            packet.macDestination = sourceRoute.get(0);

            packet.optionTypes = optionTypes;
            packet.salvage = 0;
            packet.sourceRoute = sourceRoute;
            packet.segmentsLeft = sourceRoute.size();
        } else {
            packet.macDestination = packet.ipDestination;
        }

        sendWithMaintenance(packet);
    }

    // Send a packet with a SourceRoute option to the next hop.
    // This function also performs route error handling if it could not reach the
    // next hop.
    private void sendWithMaintenance(NetworkLayerPacket packet) {
        List<NetworkLayerPacket> packets = maintenanceBuffer.getOrDefault(packet.macDestination, new ArrayList<>());
        packets.add(packet);
        maintenanceBuffer.put(packet.macDestination, packets);

        sendMACAW(packet, success -> {
            if (!success) {
                handleLinkError(id, packet.macDestination);

                for (NetworkLayerPacket sentPacket : maintenanceBuffer.get(packet.macDestination)) {
                    if (!sentPacket.ipSource.equals(id)) {
                        Set<OptionType> optionTypes = new HashSet<>();
                        optionTypes.add(OptionType.RouteError);

                        NetworkLayerPacket routeErrorPacket = new NetworkLayerPacket();

                        routeErrorPacket.sourceCoordinate = coordinate;
                        routeErrorPacket.received = new HashSet<>();

                        routeErrorPacket.ipSource = id;
                        routeErrorPacket.ipDestination = packet.ipSource;
                        routeErrorPacket.timeToLive = 255;

                        routeErrorPacket.optionTypes = optionTypes;
                        routeErrorPacket.salvage = packet.salvage;
                        routeErrorPacket.errorSourceAddress = id;
                        routeErrorPacket.errorDestinationAddress = packet.ipSource;
                        routeErrorPacket.unreachableAddress = packet.macDestination;

                        originatePacket(routeErrorPacket, false);
                    }

                    Optional<List<String>> maybeNewRoute = findRoute(packet.ipDestination);

                    if (maybeNewRoute.isPresent()) {
                        List<String> newRoute = maybeNewRoute.get();
                        newRoute.add(0, id);

                        NetworkLayerPacket salvagedPacket = packet.clone();

                        salvagedPacket.sourceCoordinate = coordinate;
                        salvagedPacket.received = new HashSet<>();

                        salvagedPacket.ipSource = id;

                        salvagedPacket.sourceRoute = newRoute;
                        salvagedPacket.segmentsLeft = newRoute.size();
                        salvagedPacket.salvage = packet.salvage + 1;

                        originatePacket(salvagedPacket, false);
                    }
                }
            }

            maintenanceBuffer.get(packet.macDestination).clear();
        });
    }

    // Process a received packet.
    private Optional<NetworkLayerPacket> receivePacket(NetworkLayerPacket packet) {
        if (packet.ipSource.equals(id)) {
            return Optional.empty();
        }

        if (packet.optionTypes.contains(OptionType.RouteRequest)) {
            List<String> route = new ArrayList<>();
            route.add(packet.ipSource);
            route.addAll(packet.route);
            route.add(id);
            updateRoutingCache(route);

            if (packet.targetAddress.equals(id)) {
                route = new ArrayList<>();
                route.addAll(packet.route);
                route.add(packet.targetAddress);
                originateRouteReply(id, packet.ipSource, route, packet.identification);

                return Optional.empty();
            } else {
                if (packet.route.contains(id)) {
                    return Optional.empty();
                }

                // TODO maybe blacklist

                if (routeRequestInTable(packet)) {
                    return Optional.empty();
                }

                addRouteRequestEntry(packet);

                Optional<List<String>> maybeCachedRoute = findRoute(packet.targetAddress);

                if (maybeCachedRoute.isPresent()) {
                    List<String> cachedRoute = maybeCachedRoute.get();

                    boolean containsDuplicate = false;

                    if (cachedRoute.contains(packet.ipSource)) {
                        containsDuplicate = true;
                    }

                    for (String node : packet.route) {
                        if (cachedRoute.contains(node)) {
                            containsDuplicate = true;
                            break;
                        }
                    }

                    if (!containsDuplicate) {
                        route = new ArrayList<>();
                        route.addAll(packet.route);
                        route.add(id);
                        route.addAll(cachedRoute);
                        route.add(packet.targetAddress);

                        // TODO 8.2.5

                        originateRouteReply(id, packet.ipSource, route, packet.identification);

                        // TODO might need to propagate RouteRequest if other options present.

                        return Optional.empty();
                    }
                }

                packet.macSource = id;

                packet.route.add(id);
            }
        }

        if (packet.optionTypes.contains(OptionType.RouteReply)) {
            List<String> route = new ArrayList<>();
            route.add(packet.ipDestination);
            route.addAll(packet.route);
            updateRoutingCache(route);
        }

        if (packet.optionTypes.contains(OptionType.RouteError)) {
            handleLinkError(packet.errorSourceAddress, packet.unreachableAddress);
        }

        if (packet.optionTypes.contains(OptionType.AcknowledgementRequest)) {
            // TODO 8.3.3
        }

        if (packet.optionTypes.contains(OptionType.Acknowledgement)) {
            List<String> link = List.of(packet.ipSource, packet.ipDestination);
            updateRoutingCache(link);

            // TODO 8.3.3
        }

        if (packet.optionTypes.contains(OptionType.SourceRoute)) {
            List<String> route = new ArrayList<>();

            if (packet.salvage == 0) {
                route.add(packet.ipSource);
            }

            route.addAll(packet.sourceRoute);
            route.add(packet.ipDestination);
            updateRoutingCache(route);

            // TODO automatic route shortening

            if (packet.segmentsLeft == 1) {
                if (!packet.ipDestination.equals("255.255.255.255")) {
                    packet.sourceCoordinate = coordinate;
                    packet.received = new HashSet<>();

                    packet.macSource = id;
                    packet.macDestination = packet.ipDestination;

                    packet.segmentsLeft -= 1;
                }
            } else if (packet.segmentsLeft > 1) {
                int i = packet.sourceRoute.size() - packet.segmentsLeft + 1;

                if (!packet.sourceRoute.get(i).equals("255.255.255.255")
                        && !packet.ipDestination.equals("255.255.255.255")) {
                    packet.sourceCoordinate = coordinate;
                    packet.received = new HashSet<>();

                    packet.macSource = id;
                    packet.macDestination = packet.sourceRoute.get(i);

                    packet.segmentsLeft -= 1;
                }
            }
        }

        if (!packet.ipDestination.equals(id) && packet.timeToLive > 0 && !packet.isPiggyBack) {
            packet.sourceCoordinate = coordinate;
            packet.received = new HashSet<>();

            packet.timeToLive -= 1;

            originatePacket(packet, false);
        } else {
            if (packet.data != null) {
                return Optional.of(packet);
            }
        }

        return Optional.empty();
    }

    // Send a new packet with a RouteReply option.
    private void originateRouteReply(String sourceAddress, String destinationAddress, List<String> route,
            int routeRequestIdentification) {
        NetworkLayerPacket routeReplyPacket = new NetworkLayerPacket();

        routeReplyPacket.sourceCoordinate = coordinate;
        routeReplyPacket.received = new HashSet<>();

        routeReplyPacket.macSource = id;

        if (route.size() >= 2) {
            routeReplyPacket.macDestination = route.get(route.size() - 2);
        } else {
            routeReplyPacket.macDestination = destinationAddress;
        }

        routeReplyPacket.ipSource = sourceAddress;
        routeReplyPacket.ipDestination = destinationAddress;
        routeReplyPacket.timeToLive = 255;

        routeReplyPacket.optionTypes = Set.of(OptionType.RouteReply);
        routeReplyPacket.route = route;
        routeReplyPacket.identification = routeRequestIdentification;

        // TODO sleep between 0 and BroadcastJitter. 8.2.4

        originatePacket(routeReplyPacket, true);
    }

    // Update our cache by removing a link.
    private void handleLinkError(String sourceAddress, String destinationAddress) {
        List<RouteCacheEntry> entries = routeCache.getOrDefault(sourceAddress, new ArrayList<>());
        Iterator<RouteCacheEntry> iter = entries.iterator();

        while (iter.hasNext()) {
            RouteCacheEntry entry = iter.next();

            if (entry.destinationAddress.equals(destinationAddress)) {
                iter.remove();
            }
        }
    }

    // Add an entry to our route request table which keeps tracks of all our route
    // requests.
    private void addRouteRequestEntry(NetworkLayerPacket routeRequestPacket) {
        RouteRequestId routeRequestId = new RouteRequestId();
        routeRequestId.routeRequestIdentification = routeRequestPacket.identification;
        routeRequestId.targetAddress = routeRequestPacket.targetAddress;

        RouteRequestTableEntry routeRequestTableEntry = routeRequestTable.getOrDefault(routeRequestPacket.ipSource,
                new RouteRequestTableEntry());

        if (routeRequestTableEntry.routeRequests == null) {
            routeRequestTableEntry.consecutiveRequests = 0;
            routeRequestTableEntry.timeRemainingUntilNextRequest = 0;
            routeRequestTableEntry.routeRequests = new ArrayDeque<>();
        }

        routeRequestTableEntry.timeToLive = routeRequestPacket.timeToLive;
        routeRequestTableEntry.consecutiveRequests += 1;
        routeRequestTableEntry.routeRequests.add(routeRequestId);

        routeRequestTable.put(routeRequestPacket.ipSource, routeRequestTableEntry);
    }

    // Update our routing cache with the given new route.
    private void updateRoutingCache(List<String> route) {
        String sourceAddress = route.get(0);

        for (int i = 1; i < route.size(); i++) {
            String destinationAddress = route.get(i);
            List<RouteCacheEntry> neighbours = routeCache.getOrDefault(sourceAddress, new ArrayList<>());
            Iterator<RouteCacheEntry> iter = neighbours.iterator();

            while (iter.hasNext()) {
                RouteCacheEntry entry = iter.next();

                if (entry.destinationAddress.equals(destinationAddress)) {
                    iter.remove();
                }
            }

            RouteCacheEntry routeCacheEntry = new RouteCacheEntry();
            routeCacheEntry.destinationAddress = destinationAddress;
            neighbours.add(routeCacheEntry);

            routeCache.put(sourceAddress, neighbours);

            sourceAddress = destinationAddress;
        }

        // System.out.println(id + " :: " + routeCache);

        checkSendBuffer();
    }

    // Check our send buffer if we now have routes to these nodes after updating our
    // cache.
    private void checkSendBuffer() {
        Iterator<SendBufferEntry> iter = sendBuffer.iterator();

        while (iter.hasNext()) {
            SendBufferEntry entry = iter.next();

            Optional<List<String>> maybeRoute = findRoute(entry.packet.ipDestination);

            if (maybeRoute.isPresent()) {
                List<String> sourceRoute = maybeRoute.get();
                sendWithSourceRoute(entry.packet, sourceRoute);

                iter.remove();
            }
        }
    }

    // Perform route discovery.
    private void routeDiscovery(NetworkLayerPacket packet, boolean piggyBackRouteRequest) {
        NetworkLayerPacket routeRequestPacket = new NetworkLayerPacket();

        routeRequestPacket.sourceCoordinate = coordinate;
        routeRequestPacket.received = new HashSet<>();

        routeRequestPacket.macSource = id;
        routeRequestPacket.macDestination = "ff:ff:ff:ff:ff:ff";

        routeRequestPacket.ipSource = id;
        routeRequestPacket.ipDestination = "255.255.255.255";
        routeRequestPacket.timeToLive = 255;

        routeRequestPacket.optionTypes = Set.of(OptionType.RouteRequest);
        routeRequestPacket.route = new ArrayList<>();
        routeRequestPacket.identification = routeRequestIdentificationCounter;
        routeRequestPacket.targetAddress = packet.ipDestination;

        routeRequestIdentificationCounter = (routeRequestIdentificationCounter + 1) % Integer.MAX_VALUE;

        addRouteRequestEntry(routeRequestPacket);

        // TODO rate limit route requests

        if (!piggyBackRouteRequest) {
            SendBufferEntry sendBufferEntry = new SendBufferEntry();
            sendBufferEntry.packet = packet;
            sendBuffer.add(sendBufferEntry);
        } else {
            packet.macSource = routeRequestPacket.macSource;
            packet.macDestination = routeRequestPacket.macDestination;

            packet.isPiggyBack = true;

            routeRequestPacket.piggyBack = packet;
        }

        sendMACAW(routeRequestPacket);
    }

    // Check if a given routeRequest is in our route request table.
    private boolean routeRequestInTable(NetworkLayerPacket routeRequestPacket) {
        RouteRequestTableEntry routeRequestTableEntry = routeRequestTable.get(routeRequestPacket.ipSource);

        if (routeRequestTableEntry != null) {
            for (RouteRequestId id : routeRequestTableEntry.routeRequests) {
                if (id.routeRequestIdentification == routeRequestPacket.identification
                        && id.targetAddress.equals(routeRequestPacket.targetAddress)) {
                    return true;
                }
            }
        }

        return false;
    }

    // Find a route from this node to the given destination.
    private Optional<List<String>> findRoute(String destination) {
        Map<String, Integer> hops = new HashMap<>();
        Map<String, String> previous = new HashMap<>();

        List<String> queue = new ArrayList<>();

        for (String node : routeCache.keySet()) {
            if (node.equals(id)) {
                hops.put(node, 0);
            } else {
                hops.put(node, Integer.MAX_VALUE);
            }

            previous.put(node, null);
            queue.add(node);
        }

        while (!queue.isEmpty()) {
            String node = queue.get(0);
            int minHops = hops.get(node);
            int index = 0;

            for (int i = 1; i < queue.size(); i++) {
                String queuedNode = queue.get(i);
                int h = hops.get(queuedNode);

                if (h < minHops) {
                    minHops = h;
                    node = queuedNode;
                    index = i;
                }
            }

            queue.remove(index);

            for (RouteCacheEntry neighbour : routeCache.get(node)) {
                if (queue.contains(neighbour.destinationAddress)) {
                    int newHops = hops.get(node) + 1;

                    if (newHops < hops.get(neighbour.destinationAddress)) {
                        hops.put(neighbour.destinationAddress, newHops);
                        previous.put(neighbour.destinationAddress, node);

                        if (neighbour.destinationAddress.equals(destination)) {
                            queue.clear();
                        }
                    }
                }
            }
        }

        List<String> route = new ArrayList<>();

        String currentNode = destination;

        while (!currentNode.equals(id)) {
            String prev = previous.get(currentNode);

            if (prev == null) {
                return Optional.empty();
            }

            currentNode = prev;
            route.add(0, currentNode);
        }

        route.remove(0);

        return Optional.of(route);
    }

    private final class RouteCacheEntry {
        private String destinationAddress;
        private int timeout;

        @Override
        public String toString() {
            return destinationAddress;
        }
    }

    private final class SendBufferEntry {
        private NetworkLayerPacket packet;
        private int additionTime;
    }

    private final class RouteRequestTableEntry {
        private int timeToLive;
        private int consecutiveRequests;
        private int timeRemainingUntilNextRequest;
        private Queue<RouteRequestId> routeRequests;
    }

    private final class RouteRequestId {
        private int routeRequestIdentification;
        private String targetAddress;
    }

    private final class GratituousReplyTableEntry {
        private String targetAddress;
        private String sourceAddress;
        private int timeRemaining;
    }
}