package com.example.visualsimulator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Objects;
import java.util.Optional;

import javafx.application.Platform;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.util.Pair;
import java.util.List;
import java.util.Map;

public class Network implements Runnable {
    // transmission range in meters.
    final LinkedList<NetworkLayerPacket> packets = new LinkedList<>();
    Map<String, Node> nodes = new HashMap<>();

    List<Line> currentLines = new ArrayList<>();

    HelloApplication visual;

    Network(HelloApplication visual) {
        this.visual = visual;
    }

    // TODO deal with broadcast of TD messages, they're for everyone (I think).

    @Override
    public void run() {
        while (true) {
            try {
                Thread.sleep(100);
                // System.out.println("Network contents: " + this.packets.toString());
                // TODO handle collisions here. Partially random?
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    // Nodes can call this method to send a packet to the network. It can't fail, so
    // no need to return anything.
    void send(NetworkLayerPacket packet) {
        synchronized (this.packets) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

            if (packet.ipSource.equals(packet.macSource) && packet.data != null) {
                List<String> route = new ArrayList<>();

                route.add(packet.ipSource);

                if (packet.sourceRoute != null) {
                    route.addAll(packet.sourceRoute);
                }

                route.add(packet.ipDestination);

                System.out.println("Using route " + route);

                Platform.runLater(new Runnable() {
                    @Override
                    public void run() {
                        visual.pane.getChildren().removeAll(currentLines);

                        currentLines.clear();

                        Iterator<String> iter = route.iterator();

                        String current = iter.next();

                        while (iter.hasNext()) {
                            String next = iter.next();

                            Node source = nodes.get(current);
                            Node dest = nodes.get(next);

                            Line line = new Line(source.coordinate[0], source.coordinate[1], dest.coordinate[0],
                                    dest.coordinate[1]);
                            line.setStroke(Color.rgb(0, 0, 0));
                            line.setStrokeWidth(3);
                            visual.pane.getChildren().add(line);

                            current = next;

                            currentLines.add(line);
                        }
                    }
                });

            }

            // packet.print();

            // System.out.println(packet.macSource + " ---> " + packet.macDestination + ": "
            // + packet.optionTypes + " :: "
            // + packet.sourceRoute);

            // if (packet.piggyBack != null) {
            // System.out.println(
            // "piggy backed: " + packet.piggyBack.macSource + " ---> " +
            // packet.piggyBack.macDestination
            // + ": " + packet.piggyBack.optionTypes + " :: "
            // + packet.piggyBack.sourceRoute);
            // }

            this.packets.add(packet);
        }
    }

    // Nodes can call this method to receive all packets destined for them. It
    // either returns an Optional<Packet> or
    // nothing.
    Optional<NetworkLayerPacket> receive(Node node) {
        synchronized (this.packets) {
            for (NetworkLayerPacket packet : this.packets) {
                if (packet.piggyBack != null) {
                    if (!packet.piggyBack.received.contains(node) && nodeWithinRange(packet, node)
                            && !Objects.equals(node.id, packet.macSource)) {
                        // Packet destined for node, make sure the node is added to the packets received
                        // list

                        packet.piggyBack.received.add(node);
                        // Return a deep copy of the object.
                        // TODO think about this, deep copy really necessary? I think so.
                        NetworkLayerPacket transmittedPacket = packet.piggyBack.clone();
                        // System.out.println(node.id + " receives " + packet.type.toString() + " from "
                        // + packet.originID);
                        return Optional.of(transmittedPacket);
                    }
                }

                // let a node receive a packet whenever it is in range.
                if (!packet.received.contains(node) && nodeWithinRange(packet, node)
                        && !Objects.equals(node.id, packet.macSource)) {
                    // Packet destined for node, make sure the node is added to the packets received
                    // list

                    packet.received.add(node);
                    // Return a deep copy of the object.
                    // TODO think about this, deep copy really necessary? I think so.
                    NetworkLayerPacket transmittedPacket = packet.clone();
                    // System.out.println(node.id + " receives " + packet.type.toString() + " from "
                    // + packet.originID);
                    return Optional.of(transmittedPacket);
                }
            }

            return Optional.empty();
        }
    }

    // The following method will return true when a node is in range of the origin
    // of a packet.
    // (Nodes each have their own transmission range)
    boolean nodeWithinRange(NetworkLayerPacket packet, Node node) {
        double packetX = packet.sourceCoordinate[0];
        double packetY = packet.sourceCoordinate[1];

        double nodeX = node.coordinate[0];
        double nodeY = node.coordinate[1];

        double distance = Math.sqrt(Math.pow(Math.abs(packetX - nodeX), 2) + Math.pow(Math.abs(packetY - nodeY), 2));
        return distance <= node.range;
    }

    boolean nodeWithinNodeRange(Node sender, Node receiver) {
        double senderX = sender.coordinate[0];
        double senderY = sender.coordinate[1];

        double receiverX = receiver.coordinate[0];
        double receiverY = receiver.coordinate[1];

        double distance = Math
                .sqrt(Math.pow(Math.abs(senderX - receiverX), 2) + Math.pow(Math.abs(senderY - receiverY), 2));
        return distance < sender.range;
    }
}
