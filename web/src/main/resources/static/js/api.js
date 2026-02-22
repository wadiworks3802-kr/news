/**
 * api 스크립트.
 *
 * 프로젝트 기능 구현을 위한 핵심 컴포넌트.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
window.api = {
  async getNews(params, options = {}) {
    const base = window.API_BASE || "http://localhost:8080";
    const qs = new URLSearchParams(params).toString();
    const res = await fetch(`${base}/api/news?${qs}`, { signal: options.signal });
    const body = await res.json();
    if (!res.ok) {
      throw body;
    }
    return body;
  },
  async requestNewsTranslate(newsId, params = {}, options = {}) {
    const base = window.API_BASE || "http://localhost:8080";
    const qs = new URLSearchParams(params).toString();
    const url = `${base}/api/news/${encodeURIComponent(newsId)}/translate${qs ? `?${qs}` : ""}`;
    const res = await fetch(url, {
      method: "POST",
      signal: options.signal
    });
    const body = await res.json();
    if (!res.ok) {
      throw body;
    }
    return body;
  },
  async getInsight(category, period, country, options = {}) {
    const base = window.API_BASE || "http://localhost:8080";
    const qs = new URLSearchParams({ category, period, country }).toString();
    const res = await fetch(`${base}/api/insight?${qs}`, { signal: options.signal });
    const body = await res.json();
    if (!res.ok) {
      throw body;
    }
    return body;
  },
  async getStockSignals(params, options = {}) {
    const base = window.API_BASE || "http://localhost:8080";
    const qs = new URLSearchParams(params).toString();
    const res = await fetch(`${base}/api/insight/stocks?${qs}`, { signal: options.signal });
    const body = await res.json();
    if (!res.ok) {
      throw body;
    }
    return body;
  },
  async getScalpSignals(params, options = {}) {
    const base = window.API_BASE || "http://localhost:8080";
    const qs = new URLSearchParams(params).toString();
    const res = await fetch(`${base}/api/insight/signals/scalp?${qs}`, { signal: options.signal });
    const body = await res.json();
    if (!res.ok) {
      throw body;
    }
    return body;
  },
  async getSwingSignals(params, options = {}) {
    const base = window.API_BASE || "http://localhost:8080";
    const qs = new URLSearchParams(params).toString();
    const res = await fetch(`${base}/api/insight/signals/swing?${qs}`, { signal: options.signal });
    const body = await res.json();
    if (!res.ok) {
      throw body;
    }
    return body;
  },
  async getDiscoverySignals(params, options = {}) {
    const base = window.API_BASE || "http://localhost:8080";
    const qs = new URLSearchParams(params).toString();
    const res = await fetch(`${base}/api/insight/discovery?${qs}`, { signal: options.signal });
    const body = await res.json();
    if (!res.ok) {
      throw body;
    }
    return body;
  },
  async getPositionSignal(assetCode, options = {}) {
    const base = window.API_BASE || "http://localhost:8080";
    const qs = new URLSearchParams({ assetCode }).toString();
    const res = await fetch(`${base}/api/insight/signals/position?${qs}`, { signal: options.signal });
    const body = await res.json();
    if (!res.ok) {
      throw body;
    }
    return body;
  },
  async getWeeklyContext(assetCode, options = {}) {
    const base = window.API_BASE || "http://localhost:8080";
    const res = await fetch(`${base}/api/insight/assets/${encodeURIComponent(assetCode)}/weekly-context`, { signal: options.signal });
    const body = await res.json();
    if (!res.ok) {
      throw body;
    }
    return body;
  },
  async getPressureAnalysis(assetCode, options = {}) {
    const base = window.API_BASE || "http://localhost:8080";
    const res = await fetch(`${base}/api/insight/assets/${encodeURIComponent(assetCode)}/pressure-analysis`, { signal: options.signal });
    const body = await res.json();
    if (!res.ok) {
      throw body;
    }
    return body;
  },
  async getSignalDetail(signalId, options = {}) {
    const base = window.API_BASE || "http://localhost:8080";
    const res = await fetch(`${base}/api/insight/signals/${encodeURIComponent(signalId)}`, { signal: options.signal });
    const body = await res.json();
    if (!res.ok) {
      throw body;
    }
    return body;
  },
  async getAssistantDashboard(params = {}, options = {}) {
    const base = window.API_BASE || "http://localhost:8080";
    const qs = new URLSearchParams(params).toString();
    const res = await fetch(`${base}/api/insight/assistant/dashboard?${qs}`, { signal: options.signal });
    const body = await res.json();
    if (!res.ok) {
      throw body;
    }
    return body;
  },
  async getPortfolioRisk(params = {}, options = {}) {
    const base = window.API_BASE || "http://localhost:8080";
    const qs = params && Object.keys(params).length ? `?${new URLSearchParams(params).toString()}` : "";
    const res = await fetch(`${base}/api/paper-trade/risk/portfolio${qs}`, { signal: options.signal });
    const body = await res.json();
    if (!res.ok) {
      throw body;
    }
    return body;
  },
  async getLocks(options = {}) {
    const base = window.API_BASE || "http://localhost:8080";
    const res = await fetch(`${base}/api/paper-trade/locks`, { signal: options.signal });
    const body = await res.json();
    if (!res.ok) {
      throw body;
    }
    return body;
  },
  async getBacktestComparison(params, options = {}) {
    const base = window.API_BASE || "http://localhost:8080";
    const qs = new URLSearchParams(params).toString();
    const res = await fetch(`${base}/api/insight/backtest/compare?${qs}`, { signal: options.signal });
    const body = await res.json();
    if (!res.ok) {
      throw body;
    }
    return body;
  },
  async adminFetch(path, { method = "GET", params = null, body = null, signal = null, apiKey = "" } = {}) {
    const base = window.API_BASE || "http://localhost:8080";
    const qs = params ? `?${new URLSearchParams(params).toString()}` : "";
    const res = await fetch(`${base}${path}${qs}`, {
      method,
      signal,
      headers: {
        "Content-Type": "application/json",
        "X-API-KEY": apiKey
      },
      body: body ? JSON.stringify(body) : undefined
    });
    const rawText = await res.text();
    let payload;
    try {
      payload = rawText ? JSON.parse(rawText) : {};
    } catch (err) {
      payload = {
        code: `HTTP_${res.status}`,
        message: rawText || `HTTP ${res.status}`,
        trace_id: ""
      };
    }
    if (!res.ok) {
      throw payload;
    }
    return payload;
  },
  async getDiagMarketSummary(params, options = {}) {
    return this.adminFetch("/api/admin/diagnostics/market-collection/summary", {
      params,
      signal: options.signal,
      apiKey: options.apiKey || ""
    });
  },
  async getDiagMarketGaps(params, options = {}) {
    return this.adminFetch("/api/admin/diagnostics/market-collection/gaps", {
      params,
      signal: options.signal,
      apiKey: options.apiKey || ""
    });
  },
  async getDiagProviderAudit(params, options = {}) {
    return this.adminFetch("/api/admin/diagnostics/market-collection/provider-audit", {
      params,
      signal: options.signal,
      apiKey: options.apiKey || ""
    });
  },
  async getDiagQualityScore(params, options = {}) {
    return this.adminFetch("/api/admin/diagnostics/market-collection/quality-score", {
      params,
      signal: options.signal,
      apiKey: options.apiKey || ""
    });
  },
  async getDiagUniverse(params, options = {}) {
    return this.adminFetch("/api/admin/diagnostics/universe", {
      params,
      signal: options.signal,
      apiKey: options.apiKey || ""
    });
  },
  async getDiagUniverseDiversity(params, options = {}) {
    return this.adminFetch("/api/admin/diagnostics/universe/diversity", {
      params,
      signal: options.signal,
      apiKey: options.apiKey || ""
    });
  },
  async getDiagTickerAlias(params, options = {}) {
    return this.adminFetch("/api/admin/diagnostics/ticker-alias", {
      params,
      signal: options.signal,
      apiKey: options.apiKey || ""
    });
  },
  async getDiagNewsAssetMapping(params, options = {}) {
    return this.adminFetch("/api/admin/diagnostics/news-asset-mapping", {
      params,
      signal: options.signal,
      apiKey: options.apiKey || ""
    });
  },
  async getDiagSignalsAudit(params, options = {}) {
    return this.adminFetch("/api/admin/diagnostics/signals/audit", {
      params,
      signal: options.signal,
      apiKey: options.apiKey || ""
    });
  },
  async getDiagSignalsAlignment(params, options = {}) {
    return this.adminFetch("/api/admin/diagnostics/signals/alignment", {
      params,
      signal: options.signal,
      apiKey: options.apiKey || ""
    });
  },
  async getDiagSignalsConfidence(params, options = {}) {
    return this.adminFetch("/api/admin/diagnostics/signals/confidence-distribution", {
      params,
      signal: options.signal,
      apiKey: options.apiKey || ""
    });
  },
  async getDiagTrace(traceId, options = {}) {
    return this.adminFetch("/api/admin/diagnostics/trace-detail", {
      params: { trace_id: traceId, limit: options.limit || 50 },
      signal: options.signal,
      apiKey: options.apiKey || ""
    });
  },
  async getFeatureToggles(params, options = {}) {
    return this.adminFetch("/api/admin/feature-toggles", {
      params,
      signal: options.signal,
      apiKey: options.apiKey || ""
    });
  },
  async upsertFeatureToggle(payload, options = {}) {
    return this.adminFetch("/api/admin/feature-toggles", {
      method: "POST",
      body: payload,
      signal: options.signal,
      apiKey: options.apiKey || ""
    });
  },
  async patchFeatureToggle(id, payload, options = {}) {
    return this.adminFetch(`/api/admin/feature-toggles/${encodeURIComponent(id)}`, {
      method: "PATCH",
      body: payload,
      signal: options.signal,
      apiKey: options.apiKey || ""
    });
  },
  async getOrderApprovals(params, options = {}) {
    return this.adminFetch("/api/admin/order-approvals", {
      params,
      signal: options.signal,
      apiKey: options.apiKey || ""
    });
  },
  async getOrderApprovalDetail(workflowId, options = {}) {
    return this.adminFetch(`/api/admin/order-approvals/${encodeURIComponent(workflowId)}`, {
      signal: options.signal,
      apiKey: options.apiKey || ""
    });
  },
  async createOrderApprovalRecommendation(payload, options = {}) {
    return this.adminFetch("/api/admin/order-approvals/recommendations", {
      method: "POST",
      body: payload,
      signal: options.signal,
      apiKey: options.apiKey || ""
    });
  },
  async approveOrderApproval(workflowId, payload, options = {}) {
    return this.adminFetch(`/api/admin/order-approvals/${encodeURIComponent(workflowId)}/approve`, {
      method: "POST",
      body: payload,
      signal: options.signal,
      apiKey: options.apiKey || ""
    });
  },
  async rejectOrderApproval(workflowId, payload, options = {}) {
    return this.adminFetch(`/api/admin/order-approvals/${encodeURIComponent(workflowId)}/reject`, {
      method: "POST",
      body: payload,
      signal: options.signal,
      apiKey: options.apiKey || ""
    });
  },
  async requestOrderApprovalPaperOrder(workflowId, payload, options = {}) {
    return this.adminFetch(`/api/admin/order-approvals/${encodeURIComponent(workflowId)}/order-request`, {
      method: "POST",
      body: payload,
      signal: options.signal,
      apiKey: options.apiKey || ""
    });
  },
  async getOrderApprovalTrace(traceId, options = {}) {
    return this.adminFetch("/api/admin/order-approvals/trace", {
      params: { trace_id: traceId, limit: options.limit || 50 },
      signal: options.signal,
      apiKey: options.apiKey || ""
    });
  }
};
