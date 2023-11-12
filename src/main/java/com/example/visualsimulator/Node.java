package com.example.visualsimulator;

import javafx.application.Platform;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

public class Node implements Runnable {
    // this boolean keeps track of whether the transition to state.CONTEND was done
    // based on sender initiated
    // or receiver initiated (RRTS)
    // The army papers models this using two different CONTEND states.
    boolean senderInitiated = false;
    boolean receiverInitiated = false;
    String communicatingWith;
    Consumer<Boolean> callback;
    TimerTask task;

    int my_backoff;
    Map<String, Integer> local_backoff = new HashMap<>();
    Map<String, Integer> remote_backoff = new HashMap<>();
    Map<String, Integer> exchange_seq_number = new HashMap<>();
    Map<String, Integer> retry_count = new HashMap<>();
    String id;
    double transmissionRange;
    double[] coordinate;
    Network network;
    String dataToSend = "";
    NetworkLayerPacket currentlySendingNLPacket;
    private state current_state = state.IDLE;
    private final HashSet<LinkLayerPacket> acknowledgesPackets = new HashSet<>();

    private final Map<String, List<RouteCacheEntry>> routeCache;
    private final ArrayDeque<SendBufferEntry> sendBuffer;
    private final Map<String, RouteRequestTableEntry> routeRequestTable;
    private int routeRequestIdentificationCounter;
    private final List<GratituousReplyTableEntry> gratituousReplyTable;
    private final Map<String, List<NetworkLayerPacket>> maintenanceBuffer;

    public void clearCache() {
        this.routeCache.clear();
    }

    public Node(String id, double transmissionRange, double[] coordinate, Network network) {
        this.id = id;
        this.transmissionRange = transmissionRange;
        this.coordinate = coordinate;
        this.network = network;

        routeCache = new HashMap<>();
        sendBuffer = new ArrayDeque<>();
        routeRequestTable = new HashMap<>();
        routeRequestIdentificationCounter = 0;
        gratituousReplyTable = new ArrayList<>();
        maintenanceBuffer = new HashMap<>();
    }

    public state getCurrent_state() {
        return this.current_state;
    }

    @Override
    public void run() {
        // TODO make sure broadcasts are not received twice and to self?
        while (true) {
            try {
                Thread.sleep(50);
                Optional<LinkLayerPacket> maybePacket = this.receiveFromLinkLayer();
                if (maybePacket.isPresent()) {
//                    this.senderInitiated = false;
                    this.receiverInitiated = false;
                    if (maybePacket.get().macDestination == "ff:ff:ff:ff:ff:ff"
                            && maybePacket.get().macSource != this.id) {
                        System.out.println("Forwarding broadcast to DSR");
                        receiveDSR(maybePacket.get());
                    } else if (maybePacket.get().type == PacketType.DATA) {
                        macawReceive(maybePacket.get());
                    }
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    void send(String receiver, String data) throws Exception {
        // TODO throw exception when in quiet state.
        if (this.current_state == state.QUIET) {
            throw new Exception("QUIET");
        }
        // System.out.println("Sending " + data + " to " + receiver);
        this.senderInitiated = true;
        this.dataToSend = data;
        this.communicatingWith = receiver;
        this.macawSend(new LinkLayerPacket(PacketType.CTS, this.coordinate, this.id, "", new HashSet<>(), this.local_backoff.getOrDefault("", 0), this.remote_backoff.getOrDefault("", 0), this.exchange_seq_number.getOrDefault("", 0)), this, success -> {});

//        this.macawSend(receiver, data, success -> {});
        // this.macawSend(new LinkLayerPacket(PacketType.CTS, this.coordinate, this.id,
        // "", new HashSet<>(), this.local_backoff.getOrDefault("", 0),
        // this.remote_backoff.getOrDefault("", 0),
        // this.exchange_seq_number.getOrDefault("", 0)), this, success -> {});
    }

    // TODO make sure data is printed when a packet has successfully arrived through
    // routing.
    // Optional<String> receiveData() {
    // Optional<NetworkLayerPacket> nlPacket = this.receiveDSR();
    // return nlPacket.map(networkLayerPacket -> networkLayerPacket.data);
    // }

    Optional<LinkLayerPacket> receiveFromLinkLayer() {
        Optional<LinkLayerPacket> packet = network.receive(this);
        if (packet.isPresent()) {
            System.out.println("Node " + this.id + " receives " + packet.get().type + " with address "
                    + packet.get().macDestination);

        }
        Optional<LinkLayerPacket> macawPacket = Optional.empty();
        if (packet.isPresent()) {
            if (packet.get().macDestination == "ff:ff:ff:ff:ff:ff") {
                // TODO checek this carefully
                System.out.println("RECEIVED BROADCAST");

//                this.current_state = state.IDLE;
                // Bypass the mac layer, instantly pass the packet to dsrReceive.
                return packet;
            } else {
                System.out.println("ELSE Node " + this.id + " receives " + packet.get().type + " with address "
                        + packet.get().macDestination + " IN STATE " + this.current_state);

                macawPacket = this.macawReceive(packet.get());
            }
        }
        return macawPacket;
    }

    // Process a packet using DSR.
    // Returns packets with data in them if any.
    // TODO Justin please check if this makes sense.
    public Optional<NetworkLayerPacket> receiveDSR(LinkLayerPacket llPacket) {
        // Optional<LinkLayerPacket> maybePacket = this.receiveFromLinkLayer();

        // if (!maybePacket.isPresent()) {
        // return Optional.empty();
        // }

        return receivePacket(llPacket.data);
    }

    // Something weird is going on. In the specification in the paper it is stated
    // that when a node sends RRTS, it goes to WFDS state. However, upon receiving
    // the RRTS packet,
    // the receiving node will send back an RTS packet. But, as soon as that RTS
    // arrives, the other node is still in WFDS state.

    // TODO in the following code, add conditions so that it is determinded whether
    // this node is the recipient of a packet or simply an "observer"
    // This method deals with handling a received packet in an appropriate way.
    Optional<LinkLayerPacket> macawReceive(LinkLayerPacket packet) {
        // This if statement deals with broadcasting. It essentially makes sure that
        System.out.println("Node " + this.id + " in state " + this.current_state + " while receiving " + packet.type + " for " + packet.macDestination);

        if (this.current_state == state.IDLE && packet.type == PacketType.RTS
                && (Objects.equals(packet.macDestination, this.id))) {
            System.out.println(this.id + " IS IN " + this.current_state);
            // This corresponds to step 2 of the paper
            // if idle and receive RTS, send Clear to send
            reassignBackoffs(packet);
            LinkLayerPacket ctsPacket = new LinkLayerPacket(PacketType.CTS, this.coordinate, this.id, packet.macSource,
                    new HashSet<>(), this.local_backoff.getOrDefault(packet.macSource, 0),
                    this.remote_backoff.getOrDefault(packet.macSource, 0),
                    this.exchange_seq_number.getOrDefault(packet.macSource, 0));
            this.current_state = state.WFDS;
            macawSend(ctsPacket, this, succss -> {
            });
            // this.network.send(ctsPacket);
            // Go to Wait for Data Send state
        } else if (this.current_state == state.WFCTS && packet.type == PacketType.CTS
                && (Objects.equals(packet.macDestination, this.id))) {
            // This corresponds to step 3
            // When in WFCTS state and receive CTS...
            task.cancel();
            reassignBackoffs(packet);

            LinkLayerPacket dsPacket = new LinkLayerPacket(PacketType.DS, this.coordinate, this.id, packet.macSource,
                    new HashSet<>(), this.local_backoff.getOrDefault(packet.macSource, 0),
                    this.remote_backoff.getOrDefault(packet.macSource, 0),
                    this.exchange_seq_number.getOrDefault(packet.macSource, 0));
            this.macawSend(dsPacket, this, success -> {
            });
            this.current_state = state.SendData;
            LinkLayerPacket dataPacket = new LinkLayerPacket(PacketType.DATA, this.coordinate, this.id,
                    packet.macSource, new HashSet<>(), this.local_backoff.getOrDefault(packet.macSource, 0),
                    this.remote_backoff.getOrDefault(packet.macSource, 0),
                    this.exchange_seq_number.getOrDefault(packet.macSource, 0), currentlySendingNLPacket);
            this.macawSend(dataPacket, this, success -> {
            });
            this.current_state = state.WFACK;
            setTimer(this, 1500);
        } else if (this.current_state == state.WFDS && packet.type == PacketType.DS
                && (Objects.equals(packet.macDestination, this.id))) {
            // Step 4
            reassignBackoffs(packet);
            this.current_state = state.WFData;
            setTimer(this, 500);
        } else if (this.current_state == state.WFData && packet.type == PacketType.DATA
                && (Objects.equals(packet.macDestination, this.id))) {
            // Step 5
            task.cancel();
            setTimer(this, 500);
            LinkLayerPacket ackPacket = new LinkLayerPacket(PacketType.ACK, this.coordinate, this.id, packet.macSource,
                    new HashSet<>(), this.local_backoff.getOrDefault(packet.macSource, 0),
                    this.remote_backoff.getOrDefault(packet.macSource, 0),
                    this.exchange_seq_number.getOrDefault(packet.macSource, 0));
            this.acknowledgesPackets.add(ackPacket);
            this.macawSend(ackPacket, this, success -> {
            });
            this.current_state = state.IDLE;
            return Optional.of(packet);
        } else if (this.current_state == state.WFACK && packet.type == PacketType.ACK
                && (Objects.equals(packet.macDestination, this.id))) {
            // Step 6
            reassignBackoffs(packet);
            this.callback.accept(true);
            task.cancel();
            this.current_state = state.IDLE;
        } else if (this.current_state == state.IDLE && packet.type == PacketType.RTS
                && this.acknowledgesPackets.contains(packet)) {
            // Step 7
            LinkLayerPacket ackPacket = new LinkLayerPacket(PacketType.ACK, this.coordinate, this.id, packet.macSource,
                    new HashSet<>(), this.local_backoff.getOrDefault(packet.macSource, 0),
                    this.remote_backoff.getOrDefault(packet.macSource, 0),
                    this.exchange_seq_number.getOrDefault(packet.macSource, 0));
            this.macawSend(ackPacket, this, success -> {
            });
        } else if (packet.type == PacketType.ACK && this.current_state == state.CONTEND) {
            // Step 8
            LinkLayerPacket ctsPacket = new LinkLayerPacket(PacketType.CTS, this.coordinate, this.id, packet.macSource,
                    new HashSet<>(), this.local_backoff.getOrDefault(packet.macSource, 0),
                    this.remote_backoff.getOrDefault(packet.macSource, 0),
                    this.exchange_seq_number.getOrDefault(packet.macSource, 0));
            this.macawSend(ctsPacket, this, success -> {
            });
            this.current_state = state.WFDS;
            setTimer(this, 500);
            // TODO This seems wrong!
        } else if (this.current_state == state.QUIET && packet.type == PacketType.RTS
                && (Objects.equals(packet.macDestination, this.id))) {
            // Step 9
            // This is transmission initiated by someone else.
            this.communicatingWith = packet.macSource;
            this.receiverInitiated = true;
            this.senderInitiated = false;
            this.current_state = state.WFCntend;
            setTimer(this, 500);
        } else if (this.current_state == state.QUIET && packet.type == PacketType.CTS
                && (Objects.equals(packet.macDestination, this.id))) {
            // Step 10
            this.current_state = state.WFCntend;
            this.remote_backoff.put(packet.macSource, packet.localBackoff);
            this.remote_backoff.put(packet.macDestination, packet.remoteBackoff);
            this.my_backoff = packet.localBackoff;
            setTimer(this, 500);
        } else if (this.current_state == state.WFCntend && (packet.type == PacketType.CTS
                || packet.type == PacketType.RTS && (Objects.equals(packet.macDestination, this.id)))) {
            // Step 11
            // TODO increase timer if necessary.
            setTimer(this, 500);
            if (packet.type != PacketType.RTS) {
                this.remote_backoff.put(packet.macSource, packet.localBackoff);
                this.remote_backoff.put(packet.macDestination, packet.remoteBackoff);
                this.my_backoff = packet.localBackoff;
            }
        } else if (this.current_state == state.WFRTS && packet.type == PacketType.RTS) {
            // Step 12
            LinkLayerPacket ctsPacket = new LinkLayerPacket(PacketType.CTS, this.coordinate, this.id, packet.macSource,
                    new HashSet<>(), this.local_backoff.getOrDefault(packet.macSource, 0),
                    this.remote_backoff.getOrDefault(packet.macSource, 0),
                    this.exchange_seq_number.getOrDefault(packet.macSource, 0));
            this.current_state = state.WFDS;
            macawSend(ctsPacket, this, success -> {
            });
            setTimer(this, 500);
        } else if (this.current_state == state.IDLE && packet.type == PacketType.RRTS
                && (Objects.equals(packet.macDestination, this.id))) {
            // Step 13
            reassignBackoffs(packet);
            LinkLayerPacket rtsPacket = new LinkLayerPacket(PacketType.RTS, this.coordinate, this.id, packet.macSource,
                    new HashSet<>(), this.my_backoff, this.remote_backoff.getOrDefault(packet.macSource, 0),
                    this.exchange_seq_number.getOrDefault(packet.macSource, 0));
            this.macawSend(rtsPacket, this, success -> {
            });
            // this.current_state = state.WFCTS;
            // setTimer(this, 200);
        } else if (packet.type == PacketType.RTS && !Objects.equals(packet.macDestination, this.id)) {
            // Defer rule 1
            this.current_state = state.QUIET;
            setTimer(this, 500);
            // TODO set a timer sufficient for A to hear B's CTS
        } else if (packet.type == PacketType.DS && !Objects.equals(packet.macDestination, this.id)) {
            // Defer rule 2
            this.current_state = state.QUIET;
            this.remote_backoff.put(packet.macSource, packet.localBackoff);
            this.remote_backoff.put(packet.macDestination, packet.remoteBackoff);
            this.my_backoff = packet.localBackoff;
            setTimer(this, 500);
            // TODO set a timer sufficient for A to transmit data and hear B's ack
        } else if (packet.type == PacketType.CTS && !Objects.equals(packet.macDestination, this.id)) {
            // Defer rule 3
            this.remote_backoff.put(packet.macSource, packet.localBackoff);
            this.remote_backoff.put(packet.macDestination, packet.remoteBackoff);
            this.current_state = state.QUIET;
            this.my_backoff = packet.localBackoff;
            setTimer(this, 500);
            // TODO set a timer suffecient for B to hear A's data.
        } else if (packet.type == PacketType.RRTS && !Objects.equals(packet.macDestination, this.id)) {
            // Defer rule 4
            this.remote_backoff.put(packet.macSource, packet.localBackoff);
            this.remote_backoff.put(packet.macDestination, packet.remoteBackoff);
            this.current_state = state.QUIET;
            this.my_backoff = packet.localBackoff;
            setTimer(this, 500);
            // TODO set a timer sufficient for an RTS-CTS exchange.
        }
        return Optional.empty();
    }

    void reassignBackoffs(LinkLayerPacket packet) {
        if (packet.sequenceNumber > this.exchange_seq_number.getOrDefault(packet.macSource, 0)) {
            this.local_backoff.put(packet.macSource, packet.remoteBackoff);
            this.remote_backoff.put(packet.macSource, packet.localBackoff);

            Integer previousSeq = this.exchange_seq_number.getOrDefault(packet.macSource, 0);
            this.exchange_seq_number.put(packet.macSource, previousSeq + 1);
            this.retry_count.put(packet.macSource, 1);
        } else {
            // Packet is a retransmission
            this.local_backoff.put(packet.macSource,
                    packet.localBackoff + packet.remoteBackoff - this.remote_backoff.getOrDefault(packet.macSource, 0));
        }
    }

    // When a node wants to send something, it should call this method instead of
    // directly calling the Network
    // send method. This has to do with the MACAW implementation.
    void macawSend(LinkLayerPacket packet, Node node, Consumer<Boolean> callback) {
        this.callback = callback;
        if (packet.macDestination.equals("ff:ff:ff:ff:ff:ff")) {
            network.send(packet);
            return;
        }
        // Step 1 from paper
        if (this.current_state == state.IDLE) {
            this.current_state = state.CONTEND;
            setTimer(node, 500);
            System.out.println("Timer set....");
        } else {
            Platform.runLater(() -> network.visual.showTransmission(node, packet.type.toString()));
            network.send(packet);
        }
    }

    void reset() {
        task.cancel();
        this.current_state = state.IDLE;
        this.dataToSend = "";
//        this.senderInitiated = false;
        this.receiverInitiated = false;
    }

    void setTimer(Node node, long duration) {
        if (task != null) {
            task.cancel();
        }
        task = new TimerTask() {
            @Override
            public void run() {
                System.out.println("Node " + node.id + " expired in state " + node.current_state);
                if (node.current_state == state.WFCntend) {
                    // first timeout rule
                    node.setTimer(node, 600);
                    node.current_state = state.CONTEND;
                } else if (node.current_state == state.CONTEND) {
                    // second timeout rule
                    System.out.println("CURRENT STATE IS CONTEND");
                    // TODO this part is why C does not go back to IDLE.
                    System.out.println("sender initiated: " + node.senderInitiated);
                    System.out.println("Receiver initiated: " + node.receiverInitiated);
                    if (node.senderInitiated) {
                        System.out.println("SENDER INITIATED");
                        LinkLayerPacket rtsPacket = new LinkLayerPacket(PacketType.RTS, node.coordinate, node.id,
                                node.communicatingWith, new HashSet<>(),
                                node.local_backoff.getOrDefault(node.communicatingWith, 0),
                                node.remote_backoff.getOrDefault(node.communicatingWith, 0),
                                node.exchange_seq_number.getOrDefault(node.communicatingWith, 0), currentlySendingNLPacket);
                        node.macawSend(rtsPacket, node, success -> {});
                        node.current_state = state.WFCTS;
                        System.out.println("GOING TO WFCTS");
                        node.setTimer(node, 1600);
                    } else if (node.receiverInitiated) {
                        LinkLayerPacket rrtsPacket = new LinkLayerPacket(PacketType.RRTS, node.coordinate, node.id,
                                node.communicatingWith, new HashSet<>(),
                                node.local_backoff.getOrDefault(node.communicatingWith, 0),
                                node.remote_backoff.getOrDefault(node.communicatingWith, 0),
                                node.exchange_seq_number.getOrDefault(node.communicatingWith, 0));
                        macawSend(rrtsPacket, node, success -> {
                        });
                        // network.send(rrtsPacket);
                        // NOTE: the two papers do not correspond here. According to the army paper, the
                        // state should be IDLE.
                        // According to the other paper, the state should be WFDS. Idle makes more
                        // sense.
                        node.current_state = state.IDLE;
                        setTimer(node, 600);
                    }
                } else if (node.current_state == state.WFACK) {
                    node.callback.accept(false);
                } else {
                    node.current_state = state.IDLE;
                }
            }
        };

        Timer timer = new Timer();
        // TODO Random timer, range might not make sense.
        timer.schedule(task, ThreadLocalRandom.current().nextInt((int) duration, (int) duration + 1));
    }

    @Override
    public String toString() {
        return this.id + " coordinate: (" + this.coordinate[0] + ", " + this.coordinate[1] + "), range: "
                + this.transmissionRange;
    }

    public void sendDSR(String receiver, String data) throws Exception {
        System.out.println("sendDSR called");
        NetworkLayerPacket packet = new NetworkLayerPacket();

        packet.ipSource = id;
        packet.ipDestination = receiver;

        packet.optionTypes = Set.of();
        packet.data = data;
        this.senderInitiated = true;

        if (!(this.current_state == state.QUIET)) {
            originatePacket(packet, false);
        } else {
            throw new Exception("QUIET");
        }
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

    // This method should first construct a link layer packet from the NetworkLayer
    // packet and then
    // call the link layer to send that. Conversion within link layer send method
    // will make things very
    // complicated and unclear. First, this is a useless mock packet.
    private void originatePacket(NetworkLayerPacket packet, boolean piggyBackRouteRequest) {
        System.out.println("ORIGINATE PACKET with destination " + packet.ipDestination);
        if (Objects.equals(packet.ipDestination, "255.255.255.255")) {
            System.out.println("Case 0");
            this.currentlySendingNLPacket = packet;
            LinkLayerPacket llPacket = new LinkLayerPacket(PacketType.DATA, this.coordinate, this.id,
                    "ff:ff:ff:ff:ff:ff", new HashSet<>(), this.local_backoff.getOrDefault("", 0),
                    this.remote_backoff.getOrDefault("", 0), this.exchange_seq_number.getOrDefault("", 0), packet);
            macawSend(llPacket, this, success -> {
            });
        } else if (packet.optionTypes.contains(OptionType.SourceRoute)) {
            System.out.println("Case 1");
            sendWithMaintenance(packet);
        } else {
            System.out.println("Case 3");
            Optional<List<String>> maybeRoute = findRoute(packet.ipDestination);

            if (maybeRoute.isPresent()) {
                System.out.println("Case 4");
                List<String> sourceRoute = maybeRoute.get();
                sendWithSourceRoute(packet, sourceRoute);
            } else {
                System.out.println("Case 5");
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

    private LinkLayerPacket networkToLinkConversion(NetworkLayerPacket nlPacket) {
        return new LinkLayerPacket(PacketType.BROADCAST, this.coordinate, this.id, nlPacket.macDestination, new HashSet<>(),
                this.local_backoff.getOrDefault("", 0), this.remote_backoff.getOrDefault("", 0),
                this.exchange_seq_number.getOrDefault("", 0), nlPacket);

    }

    // Send a packet with a SourceRoute option to the next hop.
    // This function also performs route error handling if it could not reach the
    // next hop.
    private void sendWithMaintenance(NetworkLayerPacket packet) {
        List<NetworkLayerPacket> packets = maintenanceBuffer.getOrDefault(packet.macDestination, new ArrayList<>());
        packets.add(packet);
        maintenanceBuffer.put(packet.macDestination, packets);

        this.currentlySendingNLPacket = packet;
        this.communicatingWith = packet.macDestination;
        this.senderInitiated = true;
        System.out.println("Sending from here!");
        macawSend(networkToLinkConversion(packet), this, success -> {
            if (!success) {
                handleLinkError(id, packet.macDestination);

                for (NetworkLayerPacket sentPacket : maintenanceBuffer.get(packet.macDestination)) {
                    if (!Objects.equals(sentPacket.ipSource, id)) {
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
//        System.out.println("RECEIVING DSR PACKET");
//        System.out.println("DATA: " + packet.data);
//        packet.print();
        if (packet.ipSource == id) {
            return Optional.empty();
        }

        if (packet.piggyBack != null) {
            processPacket(packet.piggyBack);
        }

        boolean mayForward = processPacket(packet);

        if (mayForward && packet.ipDestination != id && !packet.isPiggyBack) {
            packet.sourceCoordinate = coordinate;
            packet.received = new HashSet<>();

            originatePacket(packet, false);
        } else {
            if (packet.data != null) {
                return Optional.of(packet);
            }
        }

        return Optional.empty();
    }

    // Process a received packet.
    // Returns true if the packet should be forwarded if applicable.
    private boolean processPacket(NetworkLayerPacket packet) {
        if (packet.optionTypes.contains(OptionType.RouteRequest)) {
            List<String> route = new ArrayList<>();
            route.add(packet.ipSource);
            route.addAll(packet.route);
            route.add(id);
            updateRoutingCache(route);

            if (packet.targetAddress == id) {
                route = new ArrayList<>();
                route.addAll(packet.route);
                route.add(packet.targetAddress);
                originateRouteReply(id, packet.ipSource, route, packet.identification);

                return false;
            } else {
                if (packet.route.contains(id)) {
                    return false;
                }

                // TODO maybe blacklist

                if (routeRequestInTable(packet)) {
                    return false;
                }

                addRouteRequestEntry(packet);

                Optional<List<String>> maybeCachedRoute = findRoute(packet.targetAddress);

                if (maybeCachedRoute.isPresent()) {
                    List<String> cachedRoute = maybeCachedRoute.get();

                    boolean containsDuplicate = cachedRoute.contains(packet.ipSource);

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

                        return false;
                    }
                }

                packet.macSource = id;

                packet.timeToLive -= 1;

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
                if (packet.ipDestination != "255.255.255.255") {
                    packet.sourceCoordinate = coordinate;
                    packet.received = new HashSet<>();

                    packet.macSource = id;
                    packet.macDestination = packet.ipDestination;

                    packet.timeToLive -= 1;

                    packet.segmentsLeft -= 1;
                }
            } else if (packet.segmentsLeft > 1) {
                int i = packet.sourceRoute.size() - packet.segmentsLeft + 1;

                if (packet.sourceRoute.get(i) != "255.255.255.255" && packet.ipDestination != "255.255.255.255") {
                    packet.sourceCoordinate = coordinate;
                    packet.received = new HashSet<>();

                    packet.macSource = id;
                    packet.macDestination = packet.sourceRoute.get(i);

                    packet.timeToLive -= 1;

                    packet.segmentsLeft -= 1;
                }
            }
        }

        return true;
    }

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

            if (entry.destinationAddress == destinationAddress) {
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

        RouteRequestTableEntry routeRequestTableEntry = routeRequestTable.getOrDefault(id,
                new RouteRequestTableEntry());

        if (routeRequestTableEntry.routeRequests == null) {
            routeRequestTableEntry.consecutiveRequests = 0;
            routeRequestTableEntry.timeRemainingUntilNextRequest = 0;
            routeRequestTableEntry.routeRequests = new ArrayDeque<>();
        }

        routeRequestTableEntry.timeToLive = routeRequestPacket.timeToLive;
        routeRequestTableEntry.consecutiveRequests += 1;
        routeRequestTableEntry.routeRequests.add(routeRequestId);
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

                if (entry.destinationAddress == destinationAddress) {
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

        this.currentlySendingNLPacket = routeRequestPacket;
        LinkLayerPacket llPacket = networkToLinkConversion(routeRequestPacket);
        System.out.println("Sending ll packet with destination " + llPacket.macDestination);

        macawSend(llPacket, this, success -> {
        });
    }

    // Check if a given routeRequest is in our route request table.
    private boolean routeRequestInTable(NetworkLayerPacket routeRequestPacket) {
        RouteRequestTableEntry routeRequestTableEntry = routeRequestTable.get(routeRequestPacket.ipSource);

        if (routeRequestTableEntry != null) {
            for (RouteRequestId id : routeRequestTableEntry.routeRequests) {
                if (id.routeRequestIdentification == routeRequestPacket.identification
                        && id.targetAddress == routeRequestPacket.targetAddress) {
                    return true;
                }
            }
        }

        return false;
    }

    private Optional<List<String>> findRoute(String destination) {
        Map<String, Integer> hops = new HashMap<>();
        Map<String, String> previous = new HashMap<>();

        List<String> queue = new ArrayList<>();

        for (String node : routeCache.keySet()) {
            if (node == id) {
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

                        if (neighbour.destinationAddress == destination) {
                            queue.clear();
                        }
                    }
                }
            }
        }

        List<String> route = new ArrayList<>();

        String currentNode = destination;

        while (currentNode != id) {
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