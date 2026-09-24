package com.sabreware.aide.aisdk.providers.anthropic

/**
 * The `tools` entries the reference's `anthropic-prepare-tools.test.ts` inline snapshots expect, one per
 * `it(...)` named below, copied with the `undefined`-valued keys dropped — `JSON.stringify` omits them,
 * so they never reach the wire and are not part of the body being pinned.
 *
 * Key order is the snapshot's (alphabetical); the assertions compare parsed objects, so the line breaks
 * carry no meaning beyond keeping the file readable.
 */
internal object AnthropicToolsFixtures {

    /** `should correctly prepare web_search_20260318 without a beta header` */
    const val WEB_SEARCH_20260318: String = """
        {"allowed_domains":["google.com"],"max_uses":10,"name":"web_search","response_inclusion":"excluded",
         "type":"web_search_20260318","user_location":{"city":"New York","type":"approximate"}}
    """

    /** `should correctly prepare web_fetch_20260318 without a beta header` */
    const val WEB_FETCH_20260318: String = """
        {"allowed_domains":["google.com"],"citations":{"enabled":true},"max_content_tokens":1000,"max_uses":10,
         "name":"web_fetch","response_inclusion":"excluded","type":"web_fetch_20260318","use_cache":false}
    """

    /** `should correctly prepare computer_20241022 tool` */
    const val COMPUTER_20241022: String = """
        {"display_height_px":600,"display_number":1,"display_width_px":800,
         "name":"computer","type":"computer_20241022"}
    """

    /** `should correctly prepare computer_20250124 tool` */
    const val COMPUTER_20250124: String = """
        {"display_height_px":768,"display_number":1,"display_width_px":1024,
         "name":"computer","type":"computer_20250124"}
    """

    /** `should correctly prepare computer_20251124 tool` */
    const val COMPUTER_20251124: String = """
        {"display_height_px":768,"display_number":1,"display_width_px":1024,
         "name":"computer","type":"computer_20251124"}
    """

    /** `should correctly prepare computer_20251124 tool with enableZoom` */
    const val COMPUTER_20251124_ZOOM: String = """
        {"display_height_px":768,"display_number":1,"display_width_px":1024,"enable_zoom":true,
         "name":"computer","type":"computer_20251124"}
    """

    /** `should correctly prepare computer_20251124 tool with enableZoom false` */
    const val COMPUTER_20251124_NO_ZOOM: String = """
        {"display_height_px":768,"display_number":1,"display_width_px":1024,"enable_zoom":false,
         "name":"computer","type":"computer_20251124"}
    """

    /** `should correctly prepare text_editor_20241022 tool` */
    const val TEXT_EDITOR_20241022: String = """{"name":"str_replace_editor","type":"text_editor_20241022"}"""

    /** `should correctly prepare bash_20241022 tool` */
    const val BASH_20241022: String = """{"name":"bash","type":"bash_20241022"}"""

    /** `should correctly prepare text_editor_20250728 with max_characters` */
    const val TEXT_EDITOR_20250728_MAX: String = """
        {"max_characters":10000,"name":"str_replace_based_edit_tool","type":"text_editor_20250728"}
    """

    /** `should correctly prepare text_editor_20250728 without max_characters` */
    const val TEXT_EDITOR_20250728: String = """
        {"name":"str_replace_based_edit_tool","type":"text_editor_20250728"}
    """

    /** `should correctly prepare web_search_20250305` */
    const val WEB_SEARCH_20250305: String = """
        {"allowed_domains":["https://www.google.com"],"max_uses":10,"name":"web_search",
         "type":"web_search_20250305","user_location":{"city":"New York","type":"approximate"}}
    """

    /** `should correctly prepare web_search_20260209` */
    const val WEB_SEARCH_20260209: String = """
        {"allowed_domains":["https://www.google.com"],"max_uses":10,"name":"web_search",
         "type":"web_search_20260209","user_location":{"city":"New York","type":"approximate"}}
    """

    /** `should correctly prepare web_fetch_20250910` */
    const val WEB_FETCH_20250910: String = """
        {"allowed_domains":["https://www.google.com"],"citations":{"enabled":true},"max_content_tokens":1000,
         "max_uses":10,"name":"web_fetch","type":"web_fetch_20250910"}
    """

    /** `should correctly prepare web_fetch_20260209` */
    const val WEB_FETCH_20260209: String = """
        {"allowed_domains":["https://www.google.com"],"citations":{"enabled":true},"max_content_tokens":1000,
         "max_uses":10,"name":"web_fetch","type":"web_fetch_20260209"}
    """

    /** `should correctly prepare tool_search_regex_20251119` */
    const val TOOL_SEARCH_REGEX: String = """
        {"name":"tool_search_tool_regex","type":"tool_search_tool_regex_20251119"}
    """

    /** `should correctly prepare tool_search_bm25_20251119` */
    const val TOOL_SEARCH_BM25: String = """
        {"name":"tool_search_tool_bm25","type":"tool_search_tool_bm25_20251119"}
    """

    /** `should correctly prepare code_execution_20260120 without beta header` */
    const val CODE_EXECUTION_20260120: String = """{"name":"code_execution","type":"code_execution_20260120"}"""

    /** `should correctly prepare advisor_20260301 with only the required model` */
    const val ADVISOR_REQUIRED: String = """{"model":"claude-opus-4-7","name":"advisor","type":"advisor_20260301"}"""

    /** `should correctly prepare advisor_20260301 with all optional args` */
    const val ADVISOR_ALL: String = """
        {"caching":{"ttl":"1h","type":"ephemeral"},"max_tokens":2048,"max_uses":5,"model":"claude-opus-4-7",
         "name":"advisor","type":"advisor_20260301"}
    """
}
