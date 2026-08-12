package com.medmission.survey.data.local

import androidx.room.TypeConverter
import com.medmission.survey.data.model.AlcoholAmount
import com.medmission.survey.data.model.Gender
import com.medmission.survey.data.model.MaritalStatus
import com.medmission.survey.data.model.MedicalHistoryItem
import com.medmission.survey.data.model.SmokingDuration
import com.medmission.survey.data.model.SmokingStatus
import com.medmission.survey.data.model.Symptom
import com.medmission.survey.data.model.SyncStatus
import com.medmission.survey.data.model.YesNoUnknown

class Converters {
    @TypeConverter fun fromSyncStatus(v: SyncStatus): String = v.name
    @TypeConverter fun toSyncStatus(v: String): SyncStatus = SyncStatus.valueOf(v)

    @TypeConverter fun fromGender(v: Gender?): String? = v?.name
    @TypeConverter fun toGender(v: String?): Gender? = v?.let { Gender.valueOf(it) }

    @TypeConverter fun fromMaritalStatus(v: MaritalStatus?): String? = v?.name
    @TypeConverter fun toMaritalStatus(v: String?): MaritalStatus? = v?.let { MaritalStatus.valueOf(it) }

    @TypeConverter fun fromYesNoUnknown(v: YesNoUnknown?): String? = v?.name
    @TypeConverter fun toYesNoUnknown(v: String?): YesNoUnknown? = v?.let { YesNoUnknown.valueOf(it) }

    @TypeConverter fun fromSmokingStatus(v: SmokingStatus?): String? = v?.name
    @TypeConverter fun toSmokingStatus(v: String?): SmokingStatus? = v?.let { SmokingStatus.valueOf(it) }

    @TypeConverter fun fromSmokingDuration(v: SmokingDuration?): String? = v?.name
    @TypeConverter fun toSmokingDuration(v: String?): SmokingDuration? = v?.let { SmokingDuration.valueOf(it) }

    @TypeConverter fun fromAlcoholAmount(v: AlcoholAmount?): String? = v?.name
    @TypeConverter fun toAlcoholAmount(v: String?): AlcoholAmount? = v?.let { AlcoholAmount.valueOf(it) }

    @TypeConverter
    fun fromMedicalHistorySet(v: Set<MedicalHistoryItem>): String = v.joinToString(",") { it.name }
    @TypeConverter
    fun toMedicalHistorySet(v: String): Set<MedicalHistoryItem> =
        if (v.isBlank()) emptySet() else v.split(",").map { MedicalHistoryItem.valueOf(it) }.toSet()

    @TypeConverter
    fun fromSymptomSet(v: Set<Symptom>): String = v.joinToString(",") { it.name }
    @TypeConverter
    fun toSymptomSet(v: String): Set<Symptom> =
        if (v.isBlank()) emptySet() else v.split(",").map { Symptom.valueOf(it) }.toSet()
}
