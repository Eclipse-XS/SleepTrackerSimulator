package com.example.sleeptrackersimulator.smart

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class RuleSource { ACCELEROMETER_X, ACCELEROMETER_Y, ACCELEROMETER_Z, MOVEMENT_INTENSITY, ML_CLASS }
enum class RuleOperator { GREATER_THAN, GREATER_OR_EQUAL, LESS_THAN, LESS_OR_EQUAL, EQUALS }
enum class RuleAction { VIBRATION_PULSE, MOCK_BLE_ALERT, VISIBLE_ALERT, CRITICAL_ALERT }

@Entity(tableName = "smart_rules", indices = [Index("enabled")])
data class SmartRuleEntity(
    @PrimaryKey val id: String,
    val name: String,
    val enabled: Boolean,
    val source: String,
    val operator: String,
    val threshold: Float?,
    val targetClass: String?,
    val action: String,
    val cooldownMillis: Long,
    val requiredConsecutiveMatches: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "smart_rule_executions",
    foreignKeys = [ForeignKey(
        entity = SmartRuleEntity::class,
        parentColumns = ["id"],
        childColumns = ["ruleId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("ruleId"), Index("timestamp")],
)
data class SmartRuleExecutionEntity(
    @PrimaryKey val executionId: String,
    val ruleId: String,
    val timestamp: Long,
    val sourceValue: String,
    val action: String,
    val result: String,
    val failureReason: String?,
)

data class SmartRule(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val source: RuleSource,
    val operator: RuleOperator,
    val threshold: Float?,
    val targetClass: String?,
    val action: RuleAction,
    val cooldownMillis: Long,
    val requiredConsecutiveMatches: Int,
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun validate() {
        require(id.isNotBlank() && name.isNotBlank())
        require(cooldownMillis in 0..3_600_000L)
        require(requiredConsecutiveMatches in 1..100)
        if (source == RuleSource.ML_CLASS) {
            require(operator == RuleOperator.EQUALS && targetClass in setOf("REST", "RESTLESS", "ACTIVE"))
            require(threshold == null)
        } else {
            require(operator != RuleOperator.EQUALS && threshold?.isFinite() == true)
            require(targetClass == null)
        }
    }
}

fun SmartRule.toEntity() = SmartRuleEntity(
    id, name, enabled, source.name, operator.name, threshold, targetClass, action.name,
    cooldownMillis, requiredConsecutiveMatches, createdAt, updatedAt,
)

fun SmartRuleEntity.toDomain() = SmartRule(
    id, name, enabled, RuleSource.valueOf(source), RuleOperator.valueOf(operator), threshold,
    targetClass, RuleAction.valueOf(action), cooldownMillis, requiredConsecutiveMatches, createdAt, updatedAt,
).also(SmartRule::validate)
