part of 'workout.dart';

class WorkoutReading {
  /// The timestamp of the [WorkoutReading] in milliseconds.
  final timestamp = DateTime.now().millisecondsSinceEpoch;

  /// The type of data collected.
  final WorkoutFeature feature;

  /// The value of the sensor reading.
  final double value;

  WorkoutReading._(this.feature, this.value);
}

enum WorkoutFeature {
  /// An unknown workout feature
  unknown,

  /// Heart rate
  heartRate,

  /// Calories burned
  calories,

  /// Steps taken
  steps,

  /// Distance traveled in meters
  distance,

  /// Speed in km/h
  speed,
}
