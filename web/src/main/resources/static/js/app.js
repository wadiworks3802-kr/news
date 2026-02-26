/**
 * 화면 초기화 및 데이터 로드 진입점.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 *
 * 개선 포인트:
 * - 요청 경합 시 이전 요청 abort + 최신 요청만 반영
 * - 주기적 자동 갱신(실시간 느낌의 비동기 새로고침)
 * - 뉴스와 주식 시그널 동시 갱신
 */
let skipNextHashReload = false;
let requestSeq = 0;
let adminRequestSeq = 0;
let assistantRequestSeq = 0;
let autoRefreshTimer = null;
const inFlightControllers = new Set();

function normalizeCategory(category, fallback) {
  if (category === "ALL") {
    return "ALL";
  }
  if (store.categories.includes(category)) {
    return category;
  }
  return fallback;
}

function normalizeTab(tab, fallback = "home") {
  if (tab === "assistant" || tab === "admin" || tab === "home") {
    return tab;
  }
  return fallback;
}

function applyHashToState(hashState) {
  const nextCategory = normalizeCategory(hashState.category, store.state.category);
  const nextView = hashState.view === "detail" ? "detail" : "home";
  const nextDetailCategory = normalizeCategory(hashState.detailCategory, "");
  const isDetailValid = nextView === "detail" && store.categories.includes(nextDetailCategory);
  const nextTab = normalizeTab(hashState.tab, store.state.activeTab);

  store.set({
    activeTab: nextTab,
    country: hashState.country || store.state.country,
    category: nextCategory,
    sort: hashState.sort || store.state.sort,
    period: hashState.period || store.state.period,
    viewLang: hashState.viewLang || store.state.viewLang,
    q: hashState.q ?? store.state.q,
    view: isDetailValid ? "detail" : "home",
    detailCategory: isDetailValid ? nextDetailCategory : ""
  });
  ui.writeFilter(store.state);
}

function resolveFetchCategories() {
  if (store.state.view === "detail" && store.state.detailCategory) {
    return [store.state.detailCategory];
  }
  return store.state.category === "ALL"
    ? store.categories
    : [store.state.category];
}

function resolvePageSize() {
  return store.state.view === "detail"
    ? store.pageSize.detail
    : store.pageSize.homeFetch;
}

function createController() {
  const controller = new AbortController();
  inFlightControllers.add(controller);
  return controller;
}

function removeController(controller) {
  inFlightControllers.delete(controller);
}

function abortInFlightRequests() {
  inFlightControllers.forEach((controller) => controller.abort());
  inFlightControllers.clear();
}

function isAbortError(err) {
  return err?.name === "AbortError" || err?.message === "The user aborted a request.";
}

function currentAdminApiKey() {
  const raw = ($("#admin-api-key").val() || store.state.adminApiKey || "").trim();
  store.set({ adminApiKey: raw });
  if (raw) {
    sessionStorage.setItem("admin_api_key", raw);
  }
  return raw;
}

function isRiskyFeatureToggle(featureKey) {
  const key = String(featureKey || "").trim().toUpperCase();
  return key === "LIVE_TRADE" || key === "AUTO_ORDER_FULLY_AUTOMATED";
}

function confirmRiskyToggleChange(featureKey, enabled) {
  if (!isRiskyFeatureToggle(featureKey)) {
    return true;
  }
  const mode = enabled ? "ON" : "OFF";
  const first = window.confirm(`[경고] ${featureKey} 토글을 ${mode}으로 변경합니다. 계속 진행할까요?`);
  if (!first) {
    return false;
  }
  return window.confirm(`[최종확인] ${featureKey} ${mode} 적용을 확정합니다.`);
}

async function requestManualNewsTranslation(newsId, buttonEl) {
  const id = String(newsId || "").trim();
  if (!id) {
    return;
  }

  const $button = $(buttonEl);
  const originalText = $button.text();
  $button.prop("disabled", true).text("번역중...");
  ui.setLiveState("선택 기사 한글 번역 요청 중...");

  try {
    const result = await api.requestNewsTranslate(id, { mode: "sync" });
    const pending = Boolean(result?.data?.translation_pending);
    ui.setLiveState(pending ? "번역 요청 처리됨 (추가 처리중)" : "한글 번역 반영 완료");
    await loadNews({ useCurrentState: true, skipPush: true, forceWhenHidden: true });
  } catch (err) {
    ui.setLiveState("한글 번역 요청 실패");
    alert(`번역 요청 실패: ${err?.message || err?.code || "요청 실패"}`);
  } finally {
    $button.prop("disabled", false).text(originalText);
  }
}

async function loadStockSignals(seq) {
  const controller = createController();
  try {
    const result = await api.getStockSignals({
      country: store.state.country,
      period: store.state.period,
      limit: 6
    }, { signal: controller.signal });

    if (seq !== requestSeq) {
      return;
    }
    store.set({
      stockSignals: result?.data || [],
      stockTraceId: result?.trace_id || "",
      stockError: null
    });
    ui.renderStockSignals(store.state.stockSignals, store.state.country, store.state.stockTraceId);
  } catch (err) {
    if (isAbortError(err) || seq !== requestSeq) {
      return;
    }
    store.set({ stockError: err });
    ui.renderStockSignalsError(err);
  } finally {
    removeController(controller);
  }
}

async function loadAdvancedPanels(seq) {
  const scalpController = createController();
  const swingController = createController();
  const discoveryController = createController();
  const riskController = createController();
  const lockController = createController();
  const backtestController = createController();
  try {
    const [scalpState, swingState, discoveryState, riskState, lockState, backtestState] = await Promise.allSettled([
      api.getScalpSignals({
        country: store.state.country,
        limit: 8
      }, { signal: scalpController.signal }).finally(() => removeController(scalpController)),
      api.getSwingSignals({
        country: store.state.country,
        limit: 8
      }, { signal: swingController.signal }).finally(() => removeController(swingController)),
      api.getDiscoverySignals({
        country: store.state.country,
        period: "6m",
        limit: 8
      }, { signal: discoveryController.signal }).finally(() => removeController(discoveryController)),
      api.getPortfolioRisk({}, { signal: riskController.signal }).finally(() => removeController(riskController)),
      api.getLocks({ signal: lockController.signal }).finally(() => removeController(lockController)),
      api.getBacktestComparison({
        country: store.state.country,
        period: "30d"
      }, { signal: backtestController.signal }).finally(() => removeController(backtestController))
    ]);

    if (seq !== requestSeq) {
      return;
    }

    const scalpRes = scalpState.status === "fulfilled" ? scalpState.value : null;
    const swingRes = swingState.status === "fulfilled" ? swingState.value : null;
    const discoveryRes = discoveryState.status === "fulfilled" ? discoveryState.value : null;
    const riskRes = riskState.status === "fulfilled" ? riskState.value : null;
    const lockRes = lockState.status === "fulfilled" ? lockState.value : null;
    const backtestRes = backtestState.status === "fulfilled" ? backtestState.value : null;

    const panelErrors = {
      scalp: scalpState.status === "rejected" ? scalpState.reason : null,
      swing: swingState.status === "rejected" ? swingState.reason : null,
      discovery: discoveryState.status === "rejected" ? discoveryState.reason : null,
      position: null
    };

    const scalpRows = Array.isArray(scalpRes?.data) ? scalpRes.data : [];
    const swingRows = Array.isArray(swingRes?.data) ? swingRes.data : [];
    const discoveryRows = Array.isArray(discoveryRes?.data) ? discoveryRes.data : [];
    const preferredPositionCandidate =
      swingRows.find((row) => row?.asset_code && !scalpRows.some((s) => s?.asset_code === row.asset_code))
      || discoveryRows.find((row) => row?.asset_code
        && !scalpRows.some((s) => s?.asset_code === row.asset_code)
        && !swingRows.some((s) => s?.asset_code === row.asset_code))
      || scalpRows[0]
      || swingRows[0]
      || discoveryRows[0]
      || null;
    const positionAssetCode = preferredPositionCandidate?.asset_code || "";

    let positionRes = null;
    if (positionAssetCode) {
      const positionController = createController();
      try {
        positionRes = await api.getPositionSignal(positionAssetCode, { signal: positionController.signal });
      } catch (err) {
        if (!isAbortError(err)) {
          panelErrors.position = err;
        }
      } finally {
        removeController(positionController);
      }
    }

    const traces = [scalpRes?.trace_id, swingRes?.trace_id, discoveryRes?.trace_id, positionRes?.trace_id]
      .filter((v) => typeof v === "string" && v.trim().length > 0);
    const hasPanelFailure = Boolean(panelErrors.scalp || panelErrors.swing || panelErrors.discovery || panelErrors.position);

    store.set({
      signalPanels: {
        scalp: scalpRes?.data || [],
        swing: swingRes?.data || [],
        discovery: discoveryRes?.data || [],
        position: positionRes?.data || null
      },
      riskPanel: riskRes?.data || null,
      lockPanel: lockRes?.data || [],
      backtestReport: backtestRes?.data || null,
      analysisTraceId: traces[0] || "",
      analysisError: hasPanelFailure ? panelErrors : null,
      analysisPanelErrors: panelErrors
    });
    ui.renderAnalysisPanels(store.state);
    ui.renderRiskPanel(store.state.riskPanel, store.state.lockPanel, store.state.backtestReport);
  } catch (err) {
    if (isAbortError(err) || seq !== requestSeq) {
      return;
    }
    store.set({
      analysisError: err,
      analysisPanelErrors: {
        scalp: err,
        swing: err,
        discovery: err,
        position: err
      }
    });
    ui.renderAnalysisError(err);
  }
}

async function loadNews(options = {}) {
  if (store.state.activeTab !== "home" && !options.forceWhenHidden) {
    const next = ui.readFilter();
    store.set({ ...next, page: 1 });
    if (store.state.activeTab === "assistant") {
      return loadAssistantDashboard({ forceWhenHidden: true });
    }
    if (store.state.activeTab === "admin") {
      skipNextHashReload = router.push(store.state);
      return loadAdminDiagnostics({ forceWhenHidden: true });
    }
    return;
  }
  const useCurrentState = options.useCurrentState === true;
  const skipPush = options.skipPush === true;
  const backgroundRefresh = options.backgroundRefresh === true;

  if (!useCurrentState) {
    const next = ui.readFilter();
    // 필터 조작 시에는 홈 피드로 복귀하여 섹션 단위 결과를 다시 보여준다.
    store.set({
      ...next,
      page: 1,
      view: "home",
      detailCategory: "",
      loading: true,
      error: null
    });
  } else if (!backgroundRefresh) {
    store.set({ loading: true, error: null });
  }

  const categories = resolveFetchCategories();
  const size = resolvePageSize();

  const seq = ++requestSeq;
  abortInFlightRequests();

  if (!backgroundRefresh) {
    ui.renderLoading(categories, store.state.view, store.state.detailCategory);
  }
  ui.setLiveState(`실시간 자동갱신 ON (${store.autoRefreshSeconds}s)`);

  if (!skipPush) {
    skipNextHashReload = router.push(store.state);
  }

  try {
    // 카테고리별 API 병렬 조회
    const responses = await Promise.all(categories.map((category) => {
      const controller = createController();
      return api.getNews({
        country: store.state.country,
        category,
        sort: store.state.sort,
        period: store.state.period,
        view_lang: store.state.viewLang,
        page: store.state.page,
        size,
        q: store.state.q
      }, { signal: controller.signal }).finally(() => removeController(controller));
    }));

    if (seq !== requestSeq) {
      return;
    }

    const sections = {};
    const sectionTotals = {};
    categories.forEach((category, idx) => {
      sections[category] = responses[idx]?.data || [];
      sectionTotals[category] = Number(responses[idx]?.meta?.total ?? sections[category].length);
    });

    // 응답 중 첫 trace_id를 화면 상태에 표시
    const traceId = responses.find((res) => res?.trace_id)?.trace_id || "";
    const translationPending = responses.reduce(
      (sum, res) => sum + Number(res?.meta?.translation_pending ?? 0),
      0
    );

    store.set({
      sections,
      sectionTotals,
      traceId,
      loading: false,
      error: null,
      translationPending
    });
    ui.renderSections(store.state.sections, store.state.traceId, store.state);
    if (translationPending > 0 && store.state.viewLang === "ko") {
      ui.setLiveState(`번역 처리 중 ${translationPending}건 · 자동갱신 ${store.autoRefreshSeconds}s`);
    }

    await loadStockSignals(seq);
    await loadAdvancedPanels(seq);
  } catch (err) {
    if (isAbortError(err) || seq !== requestSeq) {
      return;
    }
    store.set({ loading: false, error: err });
    ui.renderError(err);
  }
}

async function loadAdminDiagnostics(options = {}) {
  if (store.state.activeTab !== "admin" && !options.forceWhenHidden) {
    return;
  }
  const apiKey = currentAdminApiKey();
  if (!apiKey) {
    ui.renderAdminError({ message: "관리자 API KEY를 입력해 주세요." });
    return;
  }

  const seq = ++adminRequestSeq;
  abortInFlightRequests();
  if (!options.backgroundRefresh) {
    ui.renderAdminLoading();
  }

  try {
    const summaryController = createController();
    const gapsController = createController();
    const providerController = createController();
    const qualityController = createController();
    const universeController = createController();
    const diversityController = createController();
    const tickerController = createController();
    const mappingController = createController();
    const thumbnailController = createController();
    const auditController = createController();
    const alignmentController = createController();
    const confidenceController = createController();
    const toggleController = createController();
    const orderApprovalController = createController();

    const [
      marketSummary,
      marketGaps,
      providerAudit,
      qualityScore,
      universe,
      universeDiversity,
      tickerAlias,
      mapping,
      newsThumbnails,
      signalAudit,
      signalAlignment,
      signalConfidence,
      featureToggles,
      orderApprovals
    ] = await Promise.all([
      api.getDiagMarketSummary({
        country: store.state.country,
        hours: 24
      }, { signal: summaryController.signal, apiKey }).finally(() => removeController(summaryController)),
      api.getDiagMarketGaps({
        limit: 20
      }, { signal: gapsController.signal, apiKey }).finally(() => removeController(gapsController)),
      api.getDiagProviderAudit({
        limit: 20
      }, { signal: providerController.signal, apiKey }).finally(() => removeController(providerController)),
      api.getDiagQualityScore({
        country: store.state.country,
        days: 7
      }, { signal: qualityController.signal, apiKey }).finally(() => removeController(qualityController)),
      api.getDiagUniverse({
        country: store.state.country,
        limit: 40
      }, { signal: universeController.signal, apiKey }).finally(() => removeController(universeController)),
      api.getDiagUniverseDiversity({
        country: store.state.country,
        limit: 40
      }, { signal: diversityController.signal, apiKey }).finally(() => removeController(diversityController)),
      api.getDiagTickerAlias({
        limit: 40
      }, { signal: tickerController.signal, apiKey }).finally(() => removeController(tickerController)),
      api.getDiagNewsAssetMapping({
        country: store.state.country,
        refresh: false
      }, { signal: mappingController.signal, apiKey }).finally(() => removeController(mappingController)),
      api.getDiagNewsThumbnails({
        country: store.state.country,
        hours: 72,
        limit: 60
      }, { signal: thumbnailController.signal, apiKey }).finally(() => removeController(thumbnailController)),
      api.getDiagSignalsAudit({
        country: store.state.country,
        limit: 40
      }, { signal: auditController.signal, apiKey }).finally(() => removeController(auditController)),
      api.getDiagSignalsAlignment({
        country: store.state.country,
        hours: 24
      }, { signal: alignmentController.signal, apiKey }).finally(() => removeController(alignmentController)),
      api.getDiagSignalsConfidence({
        country: store.state.country,
        hours: 24
      }, { signal: confidenceController.signal, apiKey }).finally(() => removeController(confidenceController)),
      api.getFeatureToggles({
        limit: 40
      }, { signal: toggleController.signal, apiKey }).finally(() => removeController(toggleController))
      ,
      api.getOrderApprovals({
        country: store.state.country,
        limit: 20
      }, { signal: orderApprovalController.signal, apiKey }).finally(() => removeController(orderApprovalController))
    ]);

    if (seq !== adminRequestSeq) {
      return;
    }
    const adminDiagnostics = {
      marketSummary,
      marketGaps,
      providerAudit,
      qualityScore,
      universe,
      universeDiversity,
      tickerAlias,
      mapping,
      newsThumbnails,
      signalAudit,
      signalAlignment,
      signalConfidence,
      featureToggles,
      orderApprovals
    };
    store.set({
      adminDiagnostics,
      adminOrderApprovals: orderApprovals,
      adminError: null
    });
    ui.renderAdminDashboard(adminDiagnostics);
  } catch (err) {
    if (isAbortError(err) || seq !== adminRequestSeq) {
      return;
    }
    store.set({ adminError: err });
    ui.renderAdminError(err);
  }
}

async function loadAdminTraceDetail() {
  if (store.state.activeTab !== "admin") {
    return;
  }
  const apiKey = currentAdminApiKey();
  if (!apiKey) {
    ui.renderAdminError({ message: "관리자 API KEY를 입력해 주세요." });
    return;
  }
  const traceId = ($("#admin-trace-id").val() || "").trim();
  if (!traceId) {
    ui.renderAdminError({ message: "조회할 trace_id를 입력해 주세요." });
    return;
  }
  const controller = createController();
  try {
    const result = await api.getDiagTrace(traceId, { signal: controller.signal, apiKey, limit: 80 });
    store.set({ adminTraceDetail: result });
    ui.renderAdminTraceDetail(result);
  } catch (err) {
    if (isAbortError(err)) {
      return;
    }
    ui.renderAdminError(err);
  } finally {
    removeController(controller);
  }
}

async function loadAdminOrderApprovalDetail(workflowId, options = {}) {
  if (store.state.activeTab !== "admin" && !options.forceWhenHidden) {
    return;
  }
  const apiKey = currentAdminApiKey();
  if (!apiKey) {
    ui.renderAdminError({ message: "관리자 API KEY를 입력해 주세요." });
    return;
  }
  const controller = createController();
  try {
    const result = await api.getOrderApprovalDetail(workflowId, { signal: controller.signal, apiKey });
    store.set({ adminOrderApprovalDetail: result });
    const traceId = result?.data?.workflow?.trace_id || "";
    if (traceId) {
      await loadAdminOrderApprovalTrace(traceId, { forceWhenHidden: true, silent: true });
    }
    ui.renderAdminOrderApprovalPanel(store.state.adminOrderApprovals, store.state.adminOrderApprovalDetail, store.state.adminOrderApprovalTrace);
  } catch (err) {
    if (isAbortError(err)) {
      return;
    }
    ui.renderAdminError(err);
  } finally {
    removeController(controller);
  }
}

async function loadAdminOrderApprovalTrace(traceId, options = {}) {
  if (store.state.activeTab !== "admin" && !options.forceWhenHidden) {
    return;
  }
  const apiKey = currentAdminApiKey();
  if (!apiKey) {
    return;
  }
  const trace = (traceId || "").trim();
  if (!trace) {
    return;
  }
  const controller = createController();
  try {
    const result = await api.getOrderApprovalTrace(trace, { signal: controller.signal, apiKey, limit: 80 });
    store.set({ adminOrderApprovalTrace: result });
    if (!options.silent) {
      ui.renderAdminOrderApprovalPanel(store.state.adminOrderApprovals, store.state.adminOrderApprovalDetail, store.state.adminOrderApprovalTrace);
    }
  } catch (err) {
    if (!isAbortError(err)) {
      ui.renderAdminError(err);
    }
  } finally {
    removeController(controller);
  }
}

async function createOrderApprovalRecommendation() {
  if (store.state.activeTab !== "admin") {
    return;
  }
  const apiKey = currentAdminApiKey();
  if (!apiKey) {
    ui.renderAdminError({ message: "관리자 API KEY를 입력해 주세요." });
    return;
  }
  const signalId = ($("#order-approval-signal-id").val() || "").trim();
  const orderSide = ($("#order-approval-order-side").val() || "").trim();
  const requestReason = ($("#order-approval-request-reason").val() || "").trim();
  if (!signalId) {
    ui.renderAdminError({ message: "signal_id를 입력해 주세요." });
    return;
  }
  try {
    const result = await api.createOrderApprovalRecommendation({
      signal_id: signalId,
      order_side: orderSide || null,
      request_reason: requestReason || null,
      requested_by: "admin-ui",
      allow_duplicate: false
    }, { apiKey });
    store.set({ adminOrderApprovalDetail: result });
    const trace = result?.data?.workflow?.trace_id || "";
    if (trace) {
      await loadAdminOrderApprovalTrace(trace, { forceWhenHidden: true, silent: true });
    }
    await loadAdminDiagnostics({ forceWhenHidden: true, backgroundRefresh: true });
    ui.renderAdminOrderApprovalPanel(store.state.adminOrderApprovals, store.state.adminOrderApprovalDetail, store.state.adminOrderApprovalTrace);
  } catch (err) {
    ui.renderAdminError(err);
  }
}

async function refreshOrderApprovalQueue() {
  await loadAdminDiagnostics({ forceWhenHidden: true, backgroundRefresh: true });
  ui.renderAdminOrderApprovalPanel(store.state.adminOrderApprovals, store.state.adminOrderApprovalDetail, store.state.adminOrderApprovalTrace);
}

async function approveOrderApprovalWorkflow(workflowId) {
  const apiKey = currentAdminApiKey();
  if (!apiKey) {
    ui.renderAdminError({ message: "관리자 API KEY를 입력해 주세요." });
    return;
  }
  const approvalReason = window.prompt("승인 사유를 입력하세요.", "관리자 승인") || "";
  if (!approvalReason.trim()) {
    return;
  }
  const autoRequestPaperOrder = window.confirm("승인 후 즉시 모의주문 요청도 실행할까요? (AUTO_ORDER_WITH_ADMIN_APPROVAL 토글 ON인 경우에만 동작)");
  try {
    const result = await api.approveOrderApproval(workflowId, {
      approved_by: "admin-ui",
      approval_reason: approvalReason.trim(),
      auto_request_paper_order: autoRequestPaperOrder
    }, { apiKey });
    store.set({ adminOrderApprovalDetail: result });
    const trace = result?.data?.workflow?.trace_id || "";
    if (trace) {
      await loadAdminOrderApprovalTrace(trace, { forceWhenHidden: true, silent: true });
    }
    await loadAdminDiagnostics({ forceWhenHidden: true, backgroundRefresh: true });
    ui.renderAdminOrderApprovalPanel(store.state.adminOrderApprovals, store.state.adminOrderApprovalDetail, store.state.adminOrderApprovalTrace);
  } catch (err) {
    ui.renderAdminError(err);
  }
}

async function rejectOrderApprovalWorkflow(workflowId) {
  const apiKey = currentAdminApiKey();
  if (!apiKey) {
    ui.renderAdminError({ message: "관리자 API KEY를 입력해 주세요." });
    return;
  }
  const rejectReason = window.prompt("반려 사유를 입력하세요.", "리스크/근거 확인 후 반려") || "";
  if (!rejectReason.trim()) {
    return;
  }
  try {
    const result = await api.rejectOrderApproval(workflowId, {
      rejected_by: "admin-ui",
      reject_reason: rejectReason.trim()
    }, { apiKey });
    store.set({ adminOrderApprovalDetail: result });
    const trace = result?.data?.workflow?.trace_id || "";
    if (trace) {
      await loadAdminOrderApprovalTrace(trace, { forceWhenHidden: true, silent: true });
    }
    await loadAdminDiagnostics({ forceWhenHidden: true, backgroundRefresh: true });
    ui.renderAdminOrderApprovalPanel(store.state.adminOrderApprovals, store.state.adminOrderApprovalDetail, store.state.adminOrderApprovalTrace);
  } catch (err) {
    ui.renderAdminError(err);
  }
}

async function requestOrderApprovalPaperOrder(workflowId) {
  const apiKey = currentAdminApiKey();
  if (!apiKey) {
    ui.renderAdminError({ message: "관리자 API KEY를 입력해 주세요." });
    return;
  }
  const requestReason = window.prompt("모의주문 요청 사유를 입력하세요.", "승인된 추천에 대한 paper order 요청") || "";
  if (!requestReason.trim()) {
    return;
  }
  try {
    const result = await api.requestOrderApprovalPaperOrder(workflowId, {
      requested_by: "admin-ui",
      request_reason: requestReason.trim(),
      force_paper: true
    }, { apiKey });
    store.set({ adminOrderApprovalDetail: result });
    const trace = result?.data?.workflow?.trace_id || "";
    if (trace) {
      await loadAdminOrderApprovalTrace(trace, { forceWhenHidden: true, silent: true });
    }
    await loadAdminDiagnostics({ forceWhenHidden: true, backgroundRefresh: true });
    ui.renderAdminOrderApprovalPanel(store.state.adminOrderApprovals, store.state.adminOrderApprovalDetail, store.state.adminOrderApprovalTrace);
  } catch (err) {
    ui.renderAdminError(err);
  }
}

function assistantStrategyRowsFromDashboard(dashboard, strategyKey) {
  const strategies = dashboard?.strategies || {};
  const strategy = strategies?.[strategyKey];
  const items = Array.isArray(strategy?.items) ? strategy.items : [];
  return items;
}

function resolveAssistantSelectedSignalId(dashboard, preferredStrategy) {
  if (!dashboard) {
    return "";
  }
  const explicit = dashboard.selected_signal_id || "";
  if (explicit) {
    return explicit;
  }
  const rows = assistantStrategyRowsFromDashboard(dashboard, preferredStrategy);
  const first = rows.find((row) => row?.signal_id);
  if (first?.signal_id) {
    return first.signal_id;
  }
  const watch = Array.isArray(dashboard.watchlist) ? dashboard.watchlist : [];
  return watch.find((row) => row?.signal_id)?.signal_id || "";
}

function ensureAssistantStrategySelection(dashboard) {
  const current = store.state.assistantSelectedStrategy;
  const strategyOrder = Array.isArray(dashboard?.strategy_order) ? dashboard.strategy_order : [];
  if (current && strategyOrder.includes(current)) {
    return current;
  }
  return strategyOrder[0] || "SCALP";
}

async function loadAssistantSignalDetail(signalId, options = {}) {
  const targetSignalId = (signalId || "").trim();
  if (!targetSignalId) {
    store.set({
      assistantSelectedSignalId: "",
      assistantDetail: null,
      assistantDetailTraceId: "",
      assistantDetailLoading: false,
      assistantQaAnswer: null,
      assistantQaTraceId: "",
      assistantQaError: null,
      assistantQaLoading: false
    });
    ui.renderAssistantDetail(null, store.state.assistantDashboard);
    return;
  }
  const controller = createController();
  try {
    if (!options.backgroundRefresh) {
      store.set({ assistantDetailLoading: true, assistantSelectedSignalId: targetSignalId });
      ui.renderAssistantDetailLoading(targetSignalId);
    } else {
      store.set({ assistantSelectedSignalId: targetSignalId, assistantDetailLoading: true });
    }
    const result = await api.getSignalDetail(targetSignalId, { signal: controller.signal });
    if (store.state.activeTab !== "assistant" && !options.forceWhenHidden) {
      return;
    }
    if ((store.state.assistantSelectedSignalId || "").trim() !== targetSignalId) {
      return;
    }
    store.set({
      assistantDetail: result?.data || null,
      assistantDetailTraceId: result?.trace_id || "",
      assistantDetailLoading: false,
      assistantQaAnswer: null,
      assistantQaTraceId: "",
      assistantQaError: null
    });
    ui.renderAssistantDetail(store.state.assistantDetail, store.state.assistantDashboard, result?.trace_id || "");
  } catch (err) {
    if (isAbortError(err)) {
      return;
    }
    store.set({ assistantDetailLoading: false });
    ui.renderAssistantDetailError(err);
  } finally {
    removeController(controller);
  }
}

async function askAssistantQuestion(forcedQuestion = "") {
  if (store.state.activeTab !== "assistant") {
    return;
  }
  const signalId = (store.state.assistantSelectedSignalId || "").trim();
  const question = (forcedQuestion || $("#assistant-qa-question").val() || "").trim();
  if (!signalId) {
    ui.renderAssistantError({ message: "질문할 종목(signal_id)을 먼저 선택해 주세요." });
    return;
  }
  if (!question) {
    store.set({ assistantQaError: { message: "질문을 입력해 주세요." } });
    ui.renderAssistantQaPanel(store.state);
    return;
  }

  const controller = createController();
  try {
    store.set({
      assistantQaQuestion: question,
      assistantQaLoading: true,
      assistantQaError: null
    });
    ui.renderAssistantQaPanel(store.state);
    const result = await api.getAssistantQa({
      signal_id: signalId,
      q: question
    }, { signal: controller.signal });
    if (store.state.activeTab !== "assistant") {
      return;
    }
    if ((store.state.assistantSelectedSignalId || "").trim() !== signalId) {
      return;
    }
    store.set({
      assistantQaAnswer: result?.data || null,
      assistantQaTraceId: result?.trace_id || "",
      assistantQaLoading: false,
      assistantQaError: null
    });
    ui.renderAssistantQaPanel(store.state);
  } catch (err) {
    if (isAbortError(err)) {
      return;
    }
    store.set({
      assistantQaLoading: false,
      assistantQaError: err
    });
    ui.renderAssistantQaPanel(store.state);
  } finally {
    removeController(controller);
  }
}

function useAssistantQaPreset(question) {
  const q = (question || "").trim();
  if (!q) {
    return;
  }
  $("#assistant-qa-question").val(q);
  store.set({ assistantQaQuestion: q });
  askAssistantQuestion(q);
}

async function loadAssistantDashboard(options = {}) {
  if (store.state.activeTab !== "assistant" && !options.forceWhenHidden) {
    return;
  }
  const seq = ++assistantRequestSeq;
  if (!options.backgroundRefresh) {
    ui.renderAssistantLoading();
  }
  if (!options.skipPush) {
    skipNextHashReload = router.push(store.state);
  }

  const controller = createController();
  try {
    const result = await api.getAssistantDashboard({
      country: store.state.country,
      limit: 8
    }, { signal: controller.signal });

    if (seq !== assistantRequestSeq) {
      return;
    }

    const dashboard = result?.data || {};
    const nextStrategy = ensureAssistantStrategySelection(dashboard);
    const nextSignalId = resolveAssistantSelectedSignalId(dashboard, nextStrategy);
    store.set({
      assistantDashboard: dashboard,
      assistantTraceId: result?.trace_id || "",
      assistantError: null,
      assistantSelectedStrategy: nextStrategy,
      assistantSelectedSignalId: nextSignalId,
      assistantSelectedAssetCode: dashboard?.selected_asset_code || "",
      assistantQaAnswer: null,
      assistantQaTraceId: "",
      assistantQaError: null,
      assistantQaLoading: false
    });
    ui.renderAssistantDashboard(store.state);

    if (nextSignalId) {
      await loadAssistantSignalDetail(nextSignalId, {
        backgroundRefresh: options.backgroundRefresh,
        forceWhenHidden: true
      });
    } else {
      store.set({ assistantDetail: null, assistantDetailTraceId: "" });
      ui.renderAssistantDetail(null, dashboard);
    }
  } catch (err) {
    if (isAbortError(err) || seq !== assistantRequestSeq) {
      return;
    }
    store.set({ assistantError: err });
    ui.renderAssistantError(err);
  } finally {
    removeController(controller);
  }
}

function changeAssistantStrategy(strategyKey) {
  const nextStrategy = (strategyKey || "").trim();
  if (!nextStrategy) {
    return;
  }
  store.set({ assistantSelectedStrategy: nextStrategy });
  ui.renderAssistantDashboard(store.state);
  const dashboard = store.state.assistantDashboard;
  const rows = assistantStrategyRowsFromDashboard(dashboard, nextStrategy);
  const nextSignalId = rows.find((row) => row?.signal_id)?.signal_id || "";
  if (nextSignalId) {
    loadAssistantSignalDetail(nextSignalId, { forceWhenHidden: true });
  } else {
    store.set({
      assistantDetail: null,
      assistantDetailTraceId: "",
      assistantSelectedSignalId: "",
      assistantQaAnswer: null,
      assistantQaTraceId: "",
      assistantQaError: null,
      assistantQaLoading: false
    });
    ui.renderAssistantDetail(null, dashboard);
  }
}

function selectAssistantSignal(signalId, assetCode) {
  store.set({
    assistantSelectedSignalId: (signalId || "").trim(),
    assistantSelectedAssetCode: (assetCode || "").trim(),
    assistantQaAnswer: null,
    assistantQaTraceId: "",
    assistantQaError: null,
    assistantQaLoading: false
  });
  ui.renderAssistantDashboard(store.state);
  if (signalId) {
    loadAssistantSignalDetail(signalId, { forceWhenHidden: true });
  }
}

async function upsertFeatureToggle() {
  if (store.state.activeTab !== "admin") {
    return;
  }
  const apiKey = currentAdminApiKey();
  if (!apiKey) {
    ui.renderAdminError({ message: "관리자 API KEY를 입력해 주세요." });
    return;
  }
  const featureKey = ($("#toggle-feature-key").val() || "").trim().toUpperCase();
  const scopeType = ($("#toggle-scope-type").val() || "GLOBAL").trim();
  const scopeValue = ($("#toggle-scope-value").val() || "").trim();
  const enabled = ($("#toggle-enabled").val() || "true") === "true";
  const reason = ($("#toggle-reason").val() || "").trim();
  if (!featureKey) {
    ui.renderAdminError({ message: "feature_key를 입력해 주세요." });
    return;
  }
  if (!confirmRiskyToggleChange(featureKey, enabled)) {
    return;
  }
  try {
    await api.upsertFeatureToggle({
      feature_key: featureKey,
      enabled,
      scope_type: scopeType,
      scope_value: scopeType === "GLOBAL" ? null : (scopeValue || null),
      reason,
      updated_by: "admin-ui"
    }, { apiKey });
    await loadAdminDiagnostics({ forceWhenHidden: true });
  } catch (err) {
    ui.renderAdminError(err);
  }
}

async function patchFeatureToggle(id, nextEnabled) {
  if (store.state.activeTab !== "admin") {
    return;
  }
  const apiKey = currentAdminApiKey();
  if (!apiKey) {
    ui.renderAdminError({ message: "관리자 API KEY를 입력해 주세요." });
    return;
  }
  const currentToggles = Array.isArray(store.state.adminDiagnostics?.featureToggles?.data)
    ? store.state.adminDiagnostics.featureToggles.data
    : [];
  const target = currentToggles.find((row) => String(row.id) === String(id));
  const featureKey = String(target?.feature_key || "").toUpperCase();
  if (!confirmRiskyToggleChange(featureKey, nextEnabled)) {
    return;
  }
  try {
    await api.patchFeatureToggle(id, {
      enabled: nextEnabled,
      reason: `admin-ui:${nextEnabled ? "enable" : "disable"}`,
      updated_by: "admin-ui"
    }, { apiKey });
    await loadAdminDiagnostics({ forceWhenHidden: true });
  } catch (err) {
    ui.renderAdminError(err);
  }
}

function switchTab(tab) {
  const nextTab = normalizeTab(tab, "home");
  if (store.state.activeTab === nextTab) {
    return;
  }
  store.set({ activeTab: nextTab });
  ui.setActiveTab(nextTab);
  skipNextHashReload = router.push(store.state);
  if (nextTab === "admin") {
    loadAdminDiagnostics({ forceWhenHidden: true });
    return;
  }
  if (nextTab === "assistant") {
    loadAssistantDashboard({ forceWhenHidden: true, skipPush: true });
    return;
  }
  loadNews({ useCurrentState: true, skipPush: true, forceWhenHidden: true });
}

function openCategoryDetail(category) {
  if (!store.categories.includes(category)) {
    return;
  }
  store.set({ view: "detail", detailCategory: category });
  loadNews({ useCurrentState: true });
}

function goHomeFeed() {
  store.set({ view: "home", detailCategory: "" });
  loadNews({ useCurrentState: true });
}

async function openSignalDetail(signalId) {
  if (!signalId) {
    return;
  }
  const controller = createController();
  try {
    const result = await api.getSignalDetail(signalId, { signal: controller.signal });
    ui.renderSignalDetailModal(result?.data || null);
  } catch (err) {
    if (isAbortError(err)) {
      return;
    }
    ui.renderError(err);
  } finally {
    removeController(controller);
  }
}

function startAutoRefresh() {
  if (autoRefreshTimer) {
    clearInterval(autoRefreshTimer);
  }
  const intervalMs = Math.max(15000, (store.autoRefreshSeconds || 30) * 1000);
  autoRefreshTimer = setInterval(() => {
    if (!store.state.autoRefreshEnabled || store.state.loading) {
      return;
    }
    if (store.state.activeTab === "admin") {
      const adminKey = ($("#admin-api-key").val() || store.state.adminApiKey || "").trim();
      if (!adminKey) {
        return;
      }
      loadAdminDiagnostics({ backgroundRefresh: true, forceWhenHidden: true });
      return;
    }
    if (store.state.activeTab === "assistant") {
      loadAssistantDashboard({ backgroundRefresh: true, forceWhenHidden: true, skipPush: true });
      return;
    }
    loadNews({ useCurrentState: true, skipPush: true, backgroundRefresh: true });
  }, intervalMs);
}

$(function () {
  const savedAdminApiKey = sessionStorage.getItem("admin_api_key");
  if (savedAdminApiKey) {
    $("#admin-api-key").val(savedAdminApiKey);
    store.set({ adminApiKey: savedAdminApiKey });
  }
  applyHashToState(router.fromHash());
  ui.setActiveTab(store.state.activeTab);
  ui.bindFilters(
    loadNews,
    openCategoryDetail,
    goHomeFeed,
    requestManualNewsTranslation,
    openSignalDetail,
    switchTab,
    () => loadAssistantDashboard({ forceWhenHidden: true, skipPush: true }),
    changeAssistantStrategy,
    selectAssistantSignal,
    () => askAssistantQuestion(),
    useAssistantQaPreset,
    () => loadAdminDiagnostics({ forceWhenHidden: true }),
    loadAdminTraceDetail,
    upsertFeatureToggle,
    patchFeatureToggle,
    refreshOrderApprovalQueue,
    createOrderApprovalRecommendation,
    loadAdminOrderApprovalDetail,
    approveOrderApprovalWorkflow,
    rejectOrderApprovalWorkflow,
    requestOrderApprovalPaperOrder,
    loadAdminOrderApprovalTrace
  );

  window.addEventListener("hashchange", () => {
    if (skipNextHashReload) {
      skipNextHashReload = false;
      return;
    }
    applyHashToState(router.fromHash());
    ui.setActiveTab(store.state.activeTab);
    if (store.state.activeTab === "admin") {
      loadAdminDiagnostics({ forceWhenHidden: true });
      return;
    }
    if (store.state.activeTab === "assistant") {
      loadAssistantDashboard({ forceWhenHidden: true, skipPush: true });
      return;
    }
    if (store.state.activeTab === "home") {
      loadNews({ useCurrentState: true, skipPush: true, forceWhenHidden: true });
    }
  });

  startAutoRefresh();
  // 최초 1회 로드
  if (store.state.activeTab === "admin") {
    loadAdminDiagnostics({ forceWhenHidden: true });
  } else if (store.state.activeTab === "assistant") {
    loadAssistantDashboard({ forceWhenHidden: true, skipPush: true });
  } else {
    loadNews({ useCurrentState: true, forceWhenHidden: true });
  }
});
