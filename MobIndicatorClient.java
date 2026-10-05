package com.example.mobindicator;

import com.fazecast.jSerialComm.SerialPort;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;

public final class MobIndicatorClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("Mob Indicator");

    // =========================================================
    // CONFIGURATION
    // =========================================================

    private static final String SERIAL_PORT_NAME = "COM6";
    private static final int SERIAL_BAUD_RATE = 115200;

    // Maximum straight-line distance from the player.
    private static final double MAX_DISTANCE = 20.0;

    // Maximum vertical difference.
    private static final double MAX_VERTICAL_DISTANCE = 10.0;

    // 2 ticks = 10 updates/second at 20 TPS.
    private static final int UPDATE_INTERVAL_TICKS = 2;

    // Send only when the servo angle changes by at least this many degrees,
    // unless the target or LED changes.
    private static final int ANGLE_CHANGE_THRESHOLD = 1;

    // The physical LED mounted on the "front" end of the arm.
    // Swap FRONT_LED/BACK_LED if the LEDs are physically reversed.
    private static final int FRONT_LED = 1;
    private static final int BACK_LED = 2;

    // Set true only if the physical servo rotates opposite to the required direction.
    private static final boolean REVERSE_SERVO = false;

    // =========================================================
    // STATE
    // =========================================================

    private static SerialPort serialPort;
    private static int tickCounter = 0;

    private static int lastServoAngle = -1;
    private static int lastLed = -1;
    private static int lastTargetId = -1;
    private static boolean lastHadTarget = false;

    @Override
    public void onInitializeClient() {
        openSerialPort();

        ClientTickEvents.END_CLIENT_TICK.register(MobIndicatorClient::onClientTick);

        LOGGER.info("Mob Indicator initialized.");
    }

    // =========================================================
    // SERIAL
    // =========================================================

    private static void openSerialPort() {
        serialPort = SerialPort.getCommPort(SERIAL_PORT_NAME);

        serialPort.setComPortParameters(
                SERIAL_BAUD_RATE,
                8,
                SerialPort.ONE_STOP_BIT,
                SerialPort.NO_PARITY
        );

        serialPort.setComPortTimeouts(
                SerialPort.TIMEOUT_NONBLOCKING,
                0,
                0
        );

        if (serialPort.openPort()) {
            LOGGER.info("Arduino connected on {}.", SERIAL_PORT_NAME);

            // Opening the USB serial port can reset a Pro Micro.
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            drainSerialInput();
            sendCommand("NONE");
        } else {
            LOGGER.error("Could not open {}.", SERIAL_PORT_NAME);
            LOGGER.info("Available serial ports:");

            for (SerialPort port : SerialPort.getCommPorts()) {
                LOGGER.info("  {} - {}", port.getSystemPortName(), port.getDescriptivePortName());
            }
        }
    }

    private static void sendCommand(String command) {
        if (serialPort == null || !serialPort.isOpen()) {
            LOGGER.warn("SEND FAILED (serial port closed): {}", command);
            return;
        }

        byte[] data = (command + "\n").getBytes(StandardCharsets.US_ASCII);
        serialPort.writeBytes(data, data.length);

        // DEBUG/diagnostic logging. This makes it easy to see exactly what
        // the Minecraft mod sends to the Arduino in latest.log.
        LOGGER.info("SEND -> {}", command);

        // Arduino replies with OK/ERR. Read and print anything already waiting.
        drainSerialInput();
    }

    private static void drainSerialInput() {
        if (serialPort == null || !serialPort.isOpen()) {
            return;
        }

        int available = serialPort.bytesAvailable();

        if (available <= 0) {
            return;
        }

        byte[] buffer = new byte[available];
        int read = serialPort.readBytes(buffer, buffer.length);

        if (read > 0) {
            String response = new String(buffer, 0, read, StandardCharsets.US_ASCII)
                    .replace("\r", "\\r")
                    .replace("\n", "\\n");

            LOGGER.info("ARDUINO <- {}", response);
        }
    }

    // =========================================================
    // CLIENT TICK
    // =========================================================

    private static void onClientTick(Minecraft client) {
        tickCounter++;

        if (tickCounter < UPDATE_INTERVAL_TICKS) {
            return;
        }

        tickCounter = 0;
        drainSerialInput();

        if (client.player == null || client.level == null) {
            setNoTarget();
            return;
        }

        Monster nearest = findNearestHostile(client);

        if (nearest == null) {
            setNoTarget();
            return;
        }

        updateIndicator(client, nearest);
    }

    // =========================================================
    // TARGET SEARCH
    // =========================================================

    private static Monster findNearestHostile(Minecraft client) {
        var player = client.player;

        AABB searchBox = player.getBoundingBox().inflate(MAX_DISTANCE);

        List<Monster> monsters = client.level.getEntitiesOfClass(
                Monster.class,
                searchBox,
                Monster::isAlive
        );

        Monster nearest = null;
        double nearestDistanceSquared = MAX_DISTANCE * MAX_DISTANCE;

        for (Monster mob : monsters) {
            // Vertical filter.
            if (Math.abs(mob.getY() - player.getY()) > MAX_VERTICAL_DISTANCE) {
                continue;
            }

            // Straight-line 3D distance filter.
            double distanceSquared = player.distanceToSqr(mob);

            if (distanceSquared > nearestDistanceSquared) {
                continue;
            }

            nearest = mob;
            nearestDistanceSquared = distanceSquared;
        }

        return nearest;
    }

    // =========================================================
    // DIRECTION / SERVO / LED
    // =========================================================

    private static void updateIndicator(Minecraft client, Monster target) {
        var player = client.player;

        // Horizontal vector from player to target.
        double dx = target.getX() - player.getX();
        double dz = target.getZ() - player.getZ();

        // Minecraft yaw:
        //   0°   = south (+Z)
        //   +90° = west  (-X)
        //   -90° = east  (+X)
        //
        // Convert player yaw to the same bearing convention as atan2(dx, dz):
        //   0°   = south
        //   +90° = east
        //   -90° = west
        double playerBearing = -player.getYRot();
        playerBearing = normalizeAngle(playerBearing);

        // Bearing of the target in world coordinates, using the same convention:
        //   0°   = south
        //   +90° = east
        //   ±180° = north
        //   -90° = west
        double targetBearing = Math.toDegrees(Math.atan2(dx, dz));

        // Signed angle from player's facing direction to target.
        // Negative = right, positive = left.
        double relativeAngle = normalizeAngle(targetBearing - playerBearing);

        int servoAngle = mapRelativeAngleToServo(relativeAngle);

        if (REVERSE_SERVO) {
            servoAngle = 180 - servoAngle;
        }

        // The two-sided arm has 180° mechanical symmetry.
        // FRONT_LED points into the player's front hemisphere (-90..+90).
        // BACK_LED points into the rear hemisphere (>90 or <-90).
        int led = Math.abs(relativeAngle) <= 90.0 ? FRONT_LED : BACK_LED;

        int targetId = target.getId();

        boolean targetChanged = targetId != lastTargetId || !lastHadTarget;
        boolean angleChanged = lastServoAngle < 0
                || Math.abs(servoAngle - lastServoAngle) >= ANGLE_CHANGE_THRESHOLD;
        boolean ledChanged = led != lastLed;

        if (targetChanged || angleChanged || ledChanged) {
            String command = String.format("TARGET,%d,%d", servoAngle, led);
            sendCommand(command);

            double distance = Math.sqrt(player.distanceToSqr(target));

            LOGGER.info(
                    "TARGET -> {} | id={} | player=({:.2f},{:.2f}) | mob=({:.2f},{:.2f}) " +
                            "| yaw={:.2f} | targetBearing={:.2f} | relative={:.2f} " +
                            "| servo={} | LED={}",
                    target.getType(),
                    targetId,
                    player.getX(),
                    player.getZ(),
                    target.getX(),
                    target.getZ(),
                    player.getYRot(),
                    targetBearing,
                    relativeAngle,
                    servoAngle,
                    led
            );

            lastTargetId = targetId;
            lastServoAngle = servoAngle;
            lastLed = led;
            lastHadTarget = true;
        }
    }

    /**
     * Folds a full -180..+180° direction onto the 0..180° physical arm.
     *
     *  relative   servo
     *     0°        90°   target straight ahead
     *   +90°       180°   target to the left
     *  +180°        90°   target directly behind
     *   -90°         0°   target to the right
     *  -180°        90°   target directly behind
     */
    private static int mapRelativeAngleToServo(double relativeAngle) {
        double servoAngle;

        if (relativeAngle >= -90.0 && relativeAngle <= 90.0) {
            servoAngle = 90.0 + relativeAngle;
        } else if (relativeAngle > 90.0) {
            servoAngle = 270.0 - relativeAngle;
        } else {
            servoAngle = -90.0 - relativeAngle;
        }

        return (int) Math.round(Math.max(0.0, Math.min(180.0, servoAngle)));
    }

    private static double normalizeAngle(double angle) {
        while (angle > 180.0) {
            angle -= 360.0;
        }

        while (angle <= -180.0) {
            angle += 360.0;
        }

        return angle;
    }

    // =========================================================
    // NO TARGET
    // =========================================================

    private static void setNoTarget() {
        if (!lastHadTarget) {
            return;
        }

        sendCommand("NONE");

        lastTargetId = -1;
        lastServoAngle = -1;
        lastLed = -1;
        lastHadTarget = false;
    }
}
