package com.sabreware.aide.aisdk.providers.async

/**
 * Kling's own wire bodies, lifted verbatim from the reference's `klingai-video-model.test.ts`. See
 * [FalFixtures] for why they live in a Kotlin file rather than a JSON resource.
 */
internal object KlingFixtures {

    const val TASK_CREATED: String = """
        {
          "code": 0,
          "message": "success",
          "request_id": "req-001",
          "data": {
            "task_id": "task-abc-123",
            "task_status": "submitted",
            "task_info": { "external_task_id": null },
            "created_at": 1722769557708,
            "updated_at": 1722769557708
          }
        }
    """

    /** Note `succeed`, not `succeeded`: matching the English past tense polls a finished job forever. */
    const val TASK_SUCCEEDED: String = """
        {
          "code": 0,
          "message": "success",
          "request_id": "req-002",
          "data": {
            "task_id": "task-abc-123",
            "task_status": "succeed",
            "task_status_msg": "",
            "task_info": { "external_task_id": null },
            "watermark_info": { "enabled": false },
            "final_unit_deduction": "1",
            "created_at": 1722769557708,
            "updated_at": 1722769560000,
            "task_result": {
              "videos": [
                {
                  "id": "video-001",
                  "url": "https://p1.a.kwimgs.com/output/video-001.mp4",
                  "watermark_url": "https://p1.a.kwimgs.com/output/video-001-watermark.mp4",
                  "duration": "5.0"
                }
              ]
            }
          }
        }
    """

    const val TASK_SUBMITTED_STATUS: String = """
        {
          "code": 0,
          "message": "success",
          "request_id": "req-003",
          "data": { "task_id": "task-abc-123", "task_status": "submitted" }
        }
    """

    const val TASK_FAILED: String = """
        {
          "code": 0,
          "message": "success",
          "request_id": "req-003",
          "data": {
            "task_id": "task-abc-123",
            "task_status": "failed",
            "task_status_msg": "The reference video is too long"
          }
        }
    """

    /** A 200 whose `data` is null — the shape that has no task to poll. */
    const val NO_TASK_ID: String = """
        { "code": 0, "message": "success", "request_id": "req-004", "data": null }
    """
}
