package com.sabreware.aide.server.api

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** Web-layer slice — boots only the MVC infrastructure + this controller, not the whole context. */
@WebMvcTest(PingController::class)
class PingControllerTests(@Autowired val mockMvc: MockMvc) {

    @Test
    fun `ping returns ok`() {
        mockMvc.perform(get("/api/ping"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("ok"))
    }
}
