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
    const [scalpRes, swingRes, discoveryRes, riskRes, lockRes, backtestRes] = await Promise.all([
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
      } finally {
        removeController(positionController);
      }
    }

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
      analysisTraceId: scalpRes?.trace_id || swingRes?.trace_id || discoveryRes?.trace_id || "",
      analysisError: null
    });
    ui.renderAnalysisPanels(store.state);
    ui.renderRiskPanel(store.state.riskPanel, store.state.lockPanel, store.state.backtestReport);
  } catch (err) {
    if (isAbortError(err) || seq !== requestSeq) {
      return;
    }
    store.set({ analysisError: err });
    $("#analysis-meta").text(`분석 오류: ${err?.message || err?.code || "요청 실패"}`);
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
    const auditController = createController();
    const alignmentController = createController();
    const confidenceController = createController();
    const toggleController = createController();

    const [
      marketSummary,
      marketGaps,
      providerAudit,
      qualityScore,
      universe,
      universeDiversity,
      tickerAlias,
      mapping,
      signalAudit,
      signalAlignment,
      signalConfidence,
      featureToggles
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
      signalAudit,
      signalAlignment,
      signalConfidence,
      featureToggles
    };
    store.set({
      adminDiagnostics,
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
      assistantDetailLoading: false
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
      assistantDetailLoading: false
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
      assistantSelectedAssetCode: dashboard?.selected_asset_code || ""
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
    store.set({ assistantDetail: null, assistantDetailTraceId: "", assistantSelectedSignalId: "" });
    ui.renderAssistantDetail(null, dashboard);
  }
}

function selectAssistantSignal(signalId, assetCode) {
  store.set({
    assistantSelectedSignalId: (signalId || "").trim(),
    assistantSelectedAssetCode: (assetCode || "").trim()
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
    openSignalDetail,
    switchTab,
    () => loadAssistantDashboard({ forceWhenHidden: true, skipPush: true }),
    changeAssistantStrategy,
    selectAssistantSignal,
    () => loadAdminDiagnostics({ forceWhenHidden: true }),
    loadAdminTraceDetail,
    upsertFeatureToggle,
    patchFeatureToggle
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
