package com.example.visualsimulator;

import java.util.HashSet;
import java.util.UUID;

public class LinkLayerPacket implements Cloneable{
    PacketType type;
    double[] originCoordinate;
    String macSource;
    String macDestination;
    // this HashSet will contain all nodes that have received this particular packet.
    // This is required to make sure that the network does not send a particular
    // packet to a node twice.
    HashSet<Node> received;
    UUID uuid = UUID.randomUUID();
    Integer localBackoff;
    Integer remoteBackoff;
    Integer sequenceNumber;
    NetworkLayerPacket data;

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + this.uuid.hashCode();
        return result;
    }

    public LinkLayerPacket(PacketType type, double[] originCoordinate, String macSource, String macDestination, HashSet<Node> received, Integer localBackoff, Integer remoteBackoff, Integer sequenceNumber, NetworkLayerPacket data) {
        this.type = type;
        this.originCoordinate = originCoordinate;
        this.macSource = macSource;
        this.macDestination = macDestination;
        this.received = received;
        this.localBackoff = localBackoff;
        this.remoteBackoff = remoteBackoff;
        this.sequenceNumber = sequenceNumber;
        this.data = data;
    }

    public LinkLayerPacket(PacketType type, double[] originCoordinate, String macSource, String macDestination, HashSet<Node> received, Integer localBackoff, Integer remoteBackoff, Integer sequenceNumber) {
        this.type = type;
        this.originCoordinate = originCoordinate;
        this.macSource= macSource;
        this.macDestination = macDestination;
        this.received = received;
        this.localBackoff = localBackoff;
        this.remoteBackoff = remoteBackoff;
        this.sequenceNumber = sequenceNumber;
    }


    @Override
    public LinkLayerPacket clone() {
        try {
            return (LinkLayerPacket) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new AssertionError();
        }
    }
}
