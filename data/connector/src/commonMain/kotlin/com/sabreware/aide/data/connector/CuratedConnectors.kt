package com.sabreware.aide.data.connector

import com.sabreware.aide.core.domain.connector.Connector
import com.sabreware.aide.core.domain.connector.ConnectorAuthType
import com.sabreware.aide.core.domain.connector.ConnectorCategory
import com.sabreware.aide.core.domain.connector.ConnectorSource

/**
 * Hand-curated overlay of popular connectors — the authoritative source of popularity, categories, and
 * correct OAuth/remote URLs the registry lacks. Sourced from the CC0 `awesome-claude-connectors` list +
 * each vendor's MCP docs. NOT a bundled asset — plain Kotlin constants. Merged over the live registry
 * (curated wins on URL/auth/category/rank). Keep the URLs current with vendor docs.
 */
object CuratedConnectors {

    val all: List<Connector> = listOf(
        curated(1, "Notion", "Search, read, and update your Notion workspace.", ConnectorCategory.PRODUCTIVITY, "https://mcp.notion.com/mcp", "notion.so", "notion"),
        curated(2, "Linear", "Create and manage Linear issues and projects.", ConnectorCategory.PRODUCTIVITY, "https://mcp.linear.app/sse", "linear.app", "linear"),
        curated(3, "GitHub", "Search repos, issues, and pull requests; manage GitHub.", ConnectorCategory.CODE, "https://api.githubcopilot.com/mcp/", "github.com", "github"),
        curated(4, "Figma", "Generate diagrams and read design context from Figma.", ConnectorCategory.DESIGN, "https://mcp.figma.com/mcp", "figma.com", "figma"),
        curated(5, "Canva", "Search, create, autofill, and export Canva designs.", ConnectorCategory.DESIGN, "https://mcp.canva.com/mcp", "canva.com", "canva"),
        curated(6, "Sentry", "Investigate errors and issues from Sentry.", ConnectorCategory.CODE, "https://mcp.sentry.dev/mcp", "sentry.io", "sentry"),
        curated(7, "Atlassian", "Work with Jira issues and Confluence pages.", ConnectorCategory.PRODUCTIVITY, "https://mcp.atlassian.com/v1/sse", "atlassian.com", "atlassian"),
        curated(8, "Asana", "Manage Asana tasks and projects.", ConnectorCategory.PRODUCTIVITY, "https://mcp.asana.com/sse", "asana.com", "asana"),
        curated(9, "Stripe", "Query payments, customers, and balances in Stripe.", ConnectorCategory.DATA, "https://mcp.stripe.com", "stripe.com", "stripe"),
        curated(10, "PayPal", "Create invoices and inspect PayPal transactions.", ConnectorCategory.DATA, "https://mcp.paypal.com/mcp", "paypal.com", "paypal"),
        curated(11, "Intercom", "Search conversations and contacts in Intercom.", ConnectorCategory.COMMUNICATION, "https://mcp.intercom.com/mcp", "intercom.com", "intercom"),
        curated(12, "Wix", "Manage your Wix site content and data.", ConnectorCategory.PRODUCTIVITY, "https://mcp.wix.com/sse", "wix.com", "wix"),
        curated(13, "Square", "Access Square catalog, orders, and payments.", ConnectorCategory.DATA, "https://mcp.squareup.com/sse", "squareup.com", "square"),
        curated(14, "Webflow", "Manage Webflow sites and CMS content.", ConnectorCategory.DESIGN, "https://mcp.webflow.com/sse", "webflow.com", "webflow"),
        // Public docs server — no auth needed.
        curated(15, "Cloudflare Docs", "Search Cloudflare's developer documentation.", ConnectorCategory.CODE, "https://docs.mcp.cloudflare.com/sse", "cloudflare.com", "cloudflare", ConnectorAuthType.NONE),
        curated(16, "Hugging Face", "Search models, datasets, and Spaces on Hugging Face.", ConnectorCategory.KNOWLEDGE, "https://huggingface.co/mcp", "huggingface.co", "huggingface", ConnectorAuthType.HEADER),
    )

    private fun curated(
        rank: Int,
        name: String,
        description: String,
        category: ConnectorCategory,
        serverUrl: String,
        brandDomain: String,
        iconSlug: String,
        authType: ConnectorAuthType = ConnectorAuthType.OAUTH,
    ): Connector = Connector(
        id = "curated:$iconSlug",
        name = name,
        description = description,
        category = category,
        serverUrl = serverUrl,
        authType = authType,
        brandDomain = brandDomain,
        iconSlug = iconSlug,
        popularityRank = rank,
        source = ConnectorSource.CURATED,
        websiteUrl = "https://$brandDomain",
    )
}
