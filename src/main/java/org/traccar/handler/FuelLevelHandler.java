package org.traccar.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.BaseDataHandler;
import org.traccar.database.FuelSensorManager;
import org.traccar.database.IdentityManager;
import org.traccar.database.ReadingTypeManager;
import org.traccar.model.Device;
import org.traccar.model.FuelSensor;
import org.traccar.model.Position;
import org.traccar.model.ReadingType;

import io.netty.channel.ChannelHandler;

@ChannelHandler.Sharable
public class FuelLevelHandler extends BaseDataHandler {
    private final IdentityManager identityManager;
    private final ReadingTypeManager readingTypeManager;
    private final FuelSensorManager fuelSensorManager;

    private static final Logger LOGGER = LoggerFactory.getLogger(FuelLevelHandler.class);

    public FuelLevelHandler(IdentityManager identityManager, ReadingTypeManager readingTypeManager,
            FuelSensorManager fuelSensorManager) {
        this.identityManager = identityManager;
        this.readingTypeManager = readingTypeManager;
        this.fuelSensorManager = fuelSensorManager;
    }

    @Override
    protected Position handlePosition(Position position) {
        if (!position.getAttributes().containsKey(Position.KEY_FUEL_LEVEL)) {
            Device device = identityManager.getById(position.getDeviceId());

            if (device != null && device.getFuelSensorId() > 0) {
                Position lastPosition = identityManager.getLastPosition(device.getId());
                FuelSensor sensor = fuelSensorManager.getById(device.getFuelSensorId());
                calculateDeviceFuelAtPosition(lastPosition, position, device, sensor);
                LOGGER.info("Device ID", device.getId());
                LOGGER.info("Fuel Level", position.getDouble(Position.KEY_FUEL_LEVEL));
            }
        }
        return position;
    }

    private void calculateDeviceFuelAtPosition(Position lastPosition, Position position, Device device,
            FuelSensor sensor) {
        if (sensor != null) {
            ReadingType readingType = readingTypeManager.getById(sensor.getReadingTypeId());

            if (sensor.getCalibrated()) {
                double fuelLevel = device.getFuelSlope() * position.getDouble(sensor.getFuelLevelPort())
                        + device.getFuelConstant();

                double boundedFuelLevel = getWithinBoundsFuelLevel(fuelLevel, sensor);
                position.set(Position.KEY_FUEL_LEVEL, boundedFuelLevel);

                double consumptionRatePerHour = calculateFuelConsumptonRaterPerHour(lastPosition, position);
                position.set(Position.KEY_FUEL_CONSUMPTION, consumptionRatePerHour);

            } else {

                double currentFuelLevel = position.getDouble(sensor.getFuelLevelPort());
                position.set(Position.KEY_FUEL_LEVEL, currentFuelLevel
                        * readingType.getConversionMultiplier());
                position.set(Position.KEY_FUEL_CONSUMPTION,
                        position.getDouble(sensor.getFuelRatePort())
                                * readingType.getConversionMultiplier());
                position.set(Position.KEY_FUEL_USED,
                        position.getDouble(sensor.getFuelConsumedPort()));
            }

            double consumptionRatePerKm = calculateFuelConsumptionRatePerKm(lastPosition, position);
            position.set(Position.KEY_FUEL_CONSUMPTION_PER_KILOMETER, consumptionRatePerKm);
        } else {
            position.set(Position.KEY_FUEL_LEVEL, 0);
            position.set(Position.KEY_FUEL_CONSUMPTION, 0);
            position.set(Position.KEY_FUEL_USED, 0);
        }
    }

    private double calculateFuelConsumptonRaterPerHour(Position lastPosition, Position position) {
        double consumptionLitresPerHour = 0; // litres/hour
        double currentFuelLevel = position.getDouble(Position.KEY_FUEL_LEVEL);
        if (currentFuelLevel > 0) {
            double lastFuelLevel = lastPosition.getDouble(Position.KEY_FUEL_LEVEL);
            double milliseccondsBetween = (position.getDeviceTime().getTime() - lastPosition.getDeviceTime().getTime());
            consumptionLitresPerHour = Math.abs(
                    (currentFuelLevel - lastFuelLevel) /* in litres */
                            / (milliseccondsBetween / (1000 * 60 * 60) /* in hours */));
        }

        return consumptionLitresPerHour;
    }

    private double calculateFuelConsumptionRatePerKm(Position lastPosition, Position position) {
        double consumptionLitresPerKilometer = 0; // litres/kilometer
        double currentFuelLevel = position.getDouble(Position.KEY_FUEL_LEVEL);

        if (currentFuelLevel > 0) {
            double lastFuelLevel = lastPosition.getDouble(Position.KEY_FUEL_LEVEL);
            consumptionLitresPerKilometer = Math.abs(
                    (currentFuelLevel - lastFuelLevel) /* in litres */
                            / ((position.getDouble(Position.KEY_ODOMETER)
                                    - lastPosition.getDouble(Position.KEY_ODOMETER))
                                    * 0.001)) /* in kilometres */;
        }

        return consumptionLitresPerKilometer;
    }

    private double getWithinBoundsFuelLevel(double fuelLevel, FuelSensor sensor) {
        if (fuelLevel < sensor.getLowerBound()) {
            return -1;
        }

        if (fuelLevel > sensor.getUpperBound()) {
            return sensor.getUpperBound();
        }

        return fuelLevel;
    }

}
