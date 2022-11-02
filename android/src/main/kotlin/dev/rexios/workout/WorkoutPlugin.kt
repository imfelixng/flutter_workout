package dev.rexios.workout

import android.os.SystemClock
import androidx.concurrent.futures.await
import androidx.health.services.client.ExerciseClient
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.coroutineScope
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.embedding.engine.plugins.lifecycle.FlutterLifecycleAdapter
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Instant

/** WorkoutPlugin */
class WorkoutPlugin : FlutterPlugin, MethodCallHandler, ActivityAware, ExerciseUpdateCallback {
    private lateinit var channel: MethodChannel
    private lateinit var lifecycleScope: CoroutineScope

    private lateinit var exerciseClient: ExerciseClient

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "workout")
        channel.setMethodCallHandler(this)

        exerciseClient =
            HealthServices.getClient(flutterPluginBinding.applicationContext).exerciseClient
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "getSupportedExerciseTypes" -> getSupportedExerciseTypes(result)
            "start" -> start(call.arguments as Map<String, Any>, result)
            "stop" -> {
                stop()
                result.success(null)
            }

            else -> result.notImplemented()
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        stop()
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        val lifecycle: Lifecycle = FlutterLifecycleAdapter.getActivityLifecycle(binding)
        lifecycleScope = lifecycle.coroutineScope

        exerciseClient.setUpdateCallback(this)
    }

    override fun onDetachedFromActivityForConfigChanges() {}
    override fun onReattachedToActivityForConfigChanges(p0: ActivityPluginBinding) {}
    override fun onDetachedFromActivity() {}

    private fun dataTypeToString(type: DataType<*, *>): String {
        return when (type) {
            DataType.HEART_RATE_BPM -> "heartRate"
            DataType.CALORIES_TOTAL -> "calories"
            DataType.STEPS_TOTAL -> "steps"
            DataType.DISTANCE -> "distance"
            DataType.SPEED -> "speed"
            else -> "unknown"
        }
    }

    private fun dataTypeFromString(string: String): DataType<*, *> {
        return when (string) {
            "heartRate" -> DataType.HEART_RATE_BPM
            "calories" -> DataType.CALORIES_TOTAL
            "steps" -> DataType.STEPS_TOTAL
            "distance" -> DataType.DISTANCE_TOTAL
            "speed" -> DataType.SPEED
            else -> throw IllegalArgumentException()
        }
    }

    private fun getSupportedExerciseTypes(result: Result) {
        lifecycleScope.launch {
            val capabilities = exerciseClient.getCapabilitiesAsync().await()
            result.success(capabilities.supportedExerciseTypes.map { it.id })
        }
    }

    private fun start(arguments: Map<String, Any>, result: Result) {
        val exerciseTypeId = arguments["exerciseType"] as Int
        val exerciseType = ExerciseType.fromId(exerciseTypeId)

        val typeStrings = arguments["sensors"] as List<String>
        val requestedDataTypes = typeStrings.map { dataTypeFromString(it) }

        val enableGps = arguments["enableGps"] as Boolean

        lifecycleScope.launch {
            val capabilities = exerciseClient.getCapabilitiesAsync().await()
            if (exerciseType !in capabilities.supportedExerciseTypes) {
                result.error("ExerciseType $exerciseType not supported", null, null)
                return@launch
            }
            val exerciseCapabilities = capabilities.getExerciseTypeCapabilities(exerciseType)
            val supportedDataTypes = exerciseCapabilities.supportedDataTypes
            val requestedUnsupportedDataTypes = requestedDataTypes.minus(supportedDataTypes)
            val requestedSupportedDataTypes = requestedDataTypes.intersect(supportedDataTypes)

            // Types for which we want to receive metrics.
            val dataTypes = requestedSupportedDataTypes.intersect(
                setOf(DataType.HEART_RATE_BPM, DataType.SPEED)
            )

            // Types for which we want to receive aggregate metrics.
            val aggregateDataTypes = requestedSupportedDataTypes.intersect(
                setOf(
                    // "Total" here refers not to the aggregation but to basal + activity.
                    DataType.CALORIES_TOTAL, DataType.STEPS, DataType.DISTANCE
                )
            )

            val config =
                ExerciseConfig(
                    exerciseType = exerciseType,
                    dataTypes = dataTypes,
                    isAutoPauseAndResumeEnabled = false,
                    isGpsEnabled = enableGps,
                )

            exerciseClient.startExerciseAsync(config).await()

            // Return the unsupported data types so the developer can handle them
            result.success(mapOf("unsupportedFeatures" to requestedUnsupportedDataTypes.map {
                dataTypeToString(
                    it
                )
            }))
        }
    }

    override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
        val data = mutableListOf<List<Any>>()
        val bootInstant =
            Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime())

        update.latestMetrics.forEach { (type, values) ->
            values.forEach { dataPoint ->
                data.add(
                    listOf(
                        dataTypeToString(type),
                        dataPoint.value.asDouble(),
                        dataPoint.getEndInstant(bootInstant).toEpochMilli()
                    )
                )
            }
        }

        update.latestAggregateMetrics.forEach { (type, value) ->
            val dataPoint = (value as CumulativeDataPoint)
            data.add(
                listOf(
                    dataTypeToString(type),
                    when {
                        dataPoint.total.isDouble -> dataPoint.total.asDouble()
                        dataPoint.total.isLong -> dataPoint.total.asLong()
                        else -> throw IllegalArgumentException()
                    },
                    // I feel like this should have getEndInstant on it like above, but whatever
                    dataPoint.endTime.toEpochMilli()
                )
            )
        }

        data.forEach {
            channel.invokeMethod("dataReceived", it)
        }
    }

    override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) {}
    override fun onRegistered() {}

    override fun onRegistrationFailed(throwable: Throwable) {}

    override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {}

    private fun stop() {
        lifecycleScope.launch {
            exerciseClient.endExercise()
        }
    }
}
