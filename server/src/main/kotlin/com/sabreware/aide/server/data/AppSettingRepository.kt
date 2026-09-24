package com.sabreware.aide.server.data

import org.springframework.data.jpa.repository.JpaRepository

interface AppSettingRepository : JpaRepository<AppSetting, String>
