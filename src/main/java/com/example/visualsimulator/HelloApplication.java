package com.example.visualsimulator;

import javafx.animation.KeyFrame;
import javafx.animation.ParallelTransition;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.util.Duration;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Timer;
import java.util.TimerTask;

public class HelloApplication extends Application {

    private static final Duration ANIMATION_DURATION = Duration.millis(10);
    HashMap<String, Integer> framecountMap = new HashMap<>();
    HashMap<String, Circle> circleMap = new HashMap<>();
    HashMap<String, Circle> rangeMap = new HashMap<>();

//    double scale = 1.0;
    LinkedList<Node> nodeList = new LinkedList<>();
    VBox nodeVBox = new VBox();
    Network network = new Network(this);
    Canvas canvas = null;
    Pane pane = null;

    @Override
    public void start(Stage stage)  {
        VBox container = new VBox();
        Scene scene = new Scene(container);
        stage.setTitle("Hello!");
        stage.setScene(scene);

        stage.setWidth(800);
        stage.setHeight(600);

        HBox numberOfNodesHBox = new HBox();
        Text numberOfNodesText = new Text("Number of nodes: ");
        TextField numberOfNodesTextField = new TextField("0");
        numberOfNodesTextField.textProperty().addListener((observable, oldValue, newValue) -> {
            if (!newValue.matches("\\d*")) {
                numberOfNodesTextField.setText(newValue.replaceAll("\\D", ""));
            }
        });

        numberOfNodesHBox.getChildren().addAll(numberOfNodesText, numberOfNodesTextField);
        container.getChildren().add(numberOfNodesHBox);

        ToggleGroup toggleGroup = new ToggleGroup();
        HBox radioHBox = new HBox();
        RadioButton randomRadio = new RadioButton("Random");
        RadioButton manualRadio = new RadioButton("Manual");

        randomRadio.setToggleGroup(toggleGroup);
        manualRadio.setToggleGroup(toggleGroup);

        canvas = new Canvas(400, 300);

        radioHBox.getChildren().addAll(randomRadio, manualRadio);

        pane = new Pane();

        EventHandler<ActionEvent> radioHandler = actionEvent -> {
            RadioButton button = (RadioButton) actionEvent.getSource();
            String option = button.getText();
            Integer numberOfNodes = Integer.parseInt(numberOfNodesTextField.getText());
            if (option.equals("Manual")) {
                nodeList.clear();
                Button addNodeButton = new Button("Add node");
                container.getChildren().add(addNodeButton);


                addNodeButton.setOnAction(addManualNodeHandler);
                addNodeButton.setUserData(pane);


            } else {
                nodeList.clear();
            }
            System.out.println("Selected " + option);
        };

        Button startSimulationButton = new Button("Start simulation");
        EventHandler<ActionEvent> startSimulationHandler = actionEvent -> {
            runSimulation();
        };
        startSimulationButton.setOnAction(startSimulationHandler);

        randomRadio.setOnAction(radioHandler);
        manualRadio.setOnAction(radioHandler);
        container.getChildren().add(radioHBox);
        container.getChildren().add(nodeVBox);
        container.getChildren().add(pane);
        container.getChildren().add(startSimulationButton);

        stage.show();
    }

    static void submitTask(Node node, String receiver, String data, Integer delay) {
        TimerTask task = new TimerTask() {
            @Override
            public void run() {
                try {
                    node.send(receiver, data);
                } catch (Exception e) {
                    e.printStackTrace();
                    System.out.println("EXCEPTION");
                    // Reschedule because node was in QUIET state
                    submitTask(node, receiver, data, delay + 200);
                }
            }
        };
        Timer timer = new Timer();
        timer.schedule(task, delay);
    }

    void runSimulation() {
        Thread networkThread = new Thread(network, "Network thread");
        networkThread.start();
        for (Node n : nodeList) {
            System.out.println(n.toString());
            Thread thread = new Thread(n, n.id);
            thread.start();
        }
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        submitTask(nodeList.get(2), "Node 3", "Helloo!", 100);
        System.out.println("Node at index 2: " + nodeList.get(2).id);
        submitTask(nodeList.get(0), "Node 1", "Goodbye!", 600);
//        while (true) {
//            System.out.println(nodeList.get(0).getCurrent_state() + " " + nodeList.get(1).getCurrent_state() + " " + nodeList.get(2).getCurrent_state() + " " + nodeList.get(3).getCurrent_state());
//            try {
//                Thread.sleep(50);
//            } catch (InterruptedException e) {
//                throw new RuntimeException(e);
//            }
//        }
    }

    void updateNodeInList(Node node) {
        for (int i = 0; i < nodeList.size(); i++) {
            Node lNode = nodeList.get(i);
            if (lNode.id.equals(node.id)) {
                nodeList.set(i, node);
                Circle circle = circleMap.getOrDefault(lNode.id, new Circle());
                circle.setLayoutX(lNode.coordinate[0]);
                circle.setLayoutY(lNode.coordinate[1]);
                circleMap.put(lNode.id, circle);

                Circle range = rangeMap.getOrDefault(lNode.id, new Circle());
                range.setLayoutX(lNode.coordinate[0]);
                range.setLayoutY(lNode.coordinate[1]);
                range.setRadius(lNode.transmissionRange);
                range.setFill(Color.TRANSPARENT);
                range.setStroke(Color.RED);
                range.setStrokeWidth(1);
                rangeMap.put(lNode.id, range);
                return;
            }
        }
    }

    void showTransmission(Node n, String message) {
        Duration duration = Duration.seconds(2);
        int finalRadius = (int) n.transmissionRange;
        Circle animationBasis = new Circle();
        animationBasis.setFill(Color.TRANSPARENT);
        animationBasis.setLayoutX(n.coordinate[0]);
        animationBasis.setLayoutY(n.coordinate[1]);
        animationBasis.setRadius(4);
        animationBasis.setStroke(Color.RED);
        animationBasis.setStrokeWidth(1);
        pane.getChildren().add(animationBasis);
        Text text = new Text(message);
        text.setLayoutX(n.coordinate[0]);
        text.setLayoutY(n.coordinate[1]);
        pane.getChildren().add(text);



        Timeline timeline = new Timeline();

        for (int frame = 0; frame <= 100; frame++) {
            Duration frameDuration = Duration.millis((frame * duration.toMillis()) / 100);
            double currentRadius = (frame / (double)100) * finalRadius;

            int finalFrame = frame;
            KeyFrame keyFrame = new KeyFrame(frameDuration, event -> {
                animationBasis.setRadius(currentRadius);
                double textAngle = (finalFrame / (double) 100) * 360;
                double textXEdge = n.coordinate[0] + currentRadius; //* Math.cos(Math.toRadians(textAngle));
                double textYEdge = n.coordinate[1] + currentRadius; //* Math.sin(Math.toRadians(textAngle));
                text.setLayoutX(textXEdge);
                text.setLayoutY(textYEdge);
            });

            timeline.getKeyFrames().add(keyFrame);
        }
        timeline.setCycleCount(1);
        timeline.play();
        EventHandler<ActionEvent> onFinished = (EventHandler) event -> pane.getChildren().remove(text);

        timeline.setOnFinished(onFinished);
    }


    void drawNodeNew(Node n, Pane pane) {
        Circle circle = new Circle();
        circle.setRadius(5);
        circle.setFill(Color.RED);
        circle.setLayoutX(n.coordinate[0]);
        circle.setLayoutY(n.coordinate[1]);
        circleMap.put(n.id, circle);

        Circle range = new Circle();
        range.setRadius(n.transmissionRange);
        range.setLayoutX(n.coordinate[0]);
        range.setLayoutY(n.coordinate[1]);
        range.setFill(Color.TRANSPARENT);
        range.setStroke(Color.RED);
        range.setStrokeWidth(1);
        rangeMap.put(n.id, range);

        pane.getChildren().add(circle);
        pane.getChildren().add(range);
    }



    private void drawCircle(double finalRadius, Node node) {
        Integer fc = framecountMap.getOrDefault(node.id, 0);
        double scale = (fc / (double)100) * (finalRadius - 1) + 1;

        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setFill(Color.TRANSPARENT);
        gc.setStroke(Color.RED);

        gc.strokeOval(node.coordinate[0] - scale, node.coordinate[1] - scale, 2 * scale * 1, 2 * scale * 1);
        framecountMap.put(node.id, (fc + 1));
    }

    void sameTime() {
        ParallelTransition transition = new ParallelTransition();


        for (Node n : nodeList) {
            transition.getChildren().add(transmitAnimation(n));
        }

        transition.play();
    }

     Timeline transmitAnimation(Node node) {

        Timeline timeline = new Timeline();

        for (int frame = 0; frame < 100; frame++) {
            Duration frameDuration = Duration.millis((frame * ANIMATION_DURATION.toMillis()) / 100.0);
            timeline.getKeyFrames().add(new KeyFrame(frameDuration, e -> drawCircle(node.transmissionRange, node)));

        }
        framecountMap.clear();

        timeline.setCycleCount(1);

        return timeline;
    }

    EventHandler<ActionEvent> addManualNodeHandler = actionEvent -> {
        Node node = new Node("Node " + nodeList.size(), 20, new double[]{200, 200}, network);
        nodeList.add(node);


        Object button = actionEvent.getSource();
        Pane pane = null;
        if (button instanceof Button) {
            pane = (Pane) ((Button) button).getUserData();
        }


        TextField nodeNameTextField = new TextField(node.id);
        TextField transmissionRangeTextField = new TextField("20");
        Pane finalPane = pane;

        drawNodeNew(node, finalPane);

        transmissionRangeTextField.textProperty().addListener((observable, oldValue, newValue) -> {
            if (!newValue.matches("\\d*") && !newValue.equals("")) {
                transmissionRangeTextField.setText(newValue.replaceAll("\\D", ""));
            } else if (!transmissionRangeTextField.getText().equals("")) {
                node.transmissionRange = Double.parseDouble(transmissionRangeTextField.getText());
                updateNodeInList(node);
            }
        });

        TextField xCoordinateTextField = new TextField(Integer.toString((int) node.coordinate[0]));
        TextField yCoordinateTextField = new TextField(Integer.toString((int) node.coordinate[1]));

        xCoordinateTextField.textProperty().addListener((observable, oldValue, newValue) -> {
            if (!newValue.matches("\\d*") && !newValue.equals("")) {
                xCoordinateTextField.setText(newValue.replaceAll("\\D", ""));
            } else if (!xCoordinateTextField.getText().equals("")) {
                node.coordinate[0] = Double.parseDouble(xCoordinateTextField.getText());
                updateNodeInList(node);
            }
        });

        yCoordinateTextField.textProperty().addListener((observable, oldValue, newValue) -> {
            if (!newValue.matches("\\d*") && !newValue.equals("")) {
                yCoordinateTextField.setText(newValue.replaceAll("\\D", ""));
            } else if (!yCoordinateTextField.getText().equals("")) {
                node.coordinate[1] = Double.parseDouble(yCoordinateTextField.getText());
                updateNodeInList(node);
            }
        });

        Button animationButton = new Button("Transmit");
        Pane finalPane3 = pane;
        EventHandler<ActionEvent> animationEvent = actionEvent1 -> {
            sameTime();
            showTransmission(node, "Message");
        };
        animationButton.setOnAction(animationEvent);

        HBox lineHbox = new HBox();
        lineHbox.getChildren().addAll(nodeNameTextField, transmissionRangeTextField, xCoordinateTextField, yCoordinateTextField, animationButton);

        nodeVBox.getChildren().add(lineHbox);

    };


    public static void main(String[] args) {
        launch();
    }
}