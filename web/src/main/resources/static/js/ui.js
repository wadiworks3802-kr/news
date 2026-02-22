/**
 * 화면 렌더링/이벤트 바인딩 유틸리티.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 *
 * API 응답을 섹션 카드로 시각화하고, 썸네일/근거스팬/원문링크를 일관된 형태로 표현한다.
 */
window.ui = {
  bindFilters(
    onChange,
    onOpenDetail,
    onBackToFeed,
    onOpenSignalDetail,
    onTabChange,
    onAssistantRefresh,
    onAssistantStrategyChange,
    onAssistantSignalSelect,
    onAdminRefresh,
    onAdminTraceLoad,
    onToggleUpsert,
    onTogglePatch,
    onOrderApprovalRefresh,
    onOrderApprovalCreate,
    onOrderApprovalDetailLoad,
    onOrderApprovalApprove,
    onOrderApprovalReject,
    onOrderApprovalOrderRequest,
    onOrderApprovalTraceLoad
  ) {
    // 변경 이벤트를 하나의 로더 함수로 연결
    ["#country", "#category", "#sort", "#period", "#viewLang"].forEach((selector) => {
      $(selector).on("change", onChange);
    });
    $("#q").on("input", onChange);
    $("#refresh").on("click", onChange);
    $("#retry").on("click", onChange);

    // 동적으로 렌더링되는 버튼은 이벤트 위임으로 처리
    $(document).on("click", ".section-more-btn", function () {
      const category = $(this).attr("data-category");
      if (category) {
        onOpenDetail(category);
      }
    });
    $(document).on("click", "#detail-back", onBackToFeed);
    $(document).on("click", ".signal-open-btn", function () {
      const signalId = $(this).attr("data-signal-id");
      if (signalId) {
        onOpenSignalDetail(signalId);
      }
    });
    $("#signal-modal-close").on("click", () => this.closeSignalModal());
    $("#signal-modal").on("click", (evt) => {
      if (evt.target && evt.target.id === "signal-modal") {
        this.closeSignalModal();
      }
    });
    $(document).on("click", ".evidence-btn", function () {
      const encoded = $(this).attr("data-evidence");
      const value = encoded ? JSON.parse(decodeURIComponent(encoded)) : [];
      alert(`evidence_spans: ${JSON.stringify(value)}`);
    });
    $("#tab-home").on("click", () => onTabChange("home"));
    $("#tab-assistant").on("click", () => onTabChange("assistant"));
    $("#tab-admin").on("click", () => onTabChange("admin"));
    $("#assistant-refresh").on("click", onAssistantRefresh);
    $(document).on("click", ".assistant-strategy-tab", function () {
      const strategy = $(this).attr("data-strategy");
      if (strategy) {
        onAssistantStrategyChange(strategy);
      }
    });
    $(document).on("click", ".assistant-watch-item, .assistant-signal-row", function () {
      const signalId = ($(this).attr("data-signal-id") || "").trim();
      const assetCode = ($(this).attr("data-asset-code") || "").trim();
      if (signalId) {
        onAssistantSignalSelect(signalId, assetCode);
      }
    });
    $("#admin-refresh").on("click", onAdminRefresh);
    $("#admin-trace-load").on("click", onAdminTraceLoad);
    $("#toggle-upsert").on("click", onToggleUpsert);
    $("#order-approval-refresh").on("click", onOrderApprovalRefresh);
    $("#order-approval-create").on("click", onOrderApprovalCreate);
    $(document).on("click", ".toggle-patch-btn", function () {
      const id = $(this).attr("data-id");
      const enabled = $(this).attr("data-enabled");
      if (id) {
        onTogglePatch(id, enabled === "true");
      }
    });
    $(document).on("click", ".order-approval-detail-btn", function () {
      const workflowId = $(this).attr("data-workflow-id");
      if (workflowId) {
        onOrderApprovalDetailLoad(workflowId);
      }
    });
    $(document).on("click", ".order-approval-approve-btn", function () {
      const workflowId = $(this).attr("data-workflow-id");
      if (workflowId) {
        onOrderApprovalApprove(workflowId);
      }
    });
    $(document).on("click", ".order-approval-reject-btn", function () {
      const workflowId = $(this).attr("data-workflow-id");
      if (workflowId) {
        onOrderApprovalReject(workflowId);
      }
    });
    $(document).on("click", ".order-approval-order-btn", function () {
      const workflowId = $(this).attr("data-workflow-id");
      if (workflowId) {
        onOrderApprovalOrderRequest(workflowId);
      }
    });
    $(document).on("click", ".order-approval-trace-btn", function () {
      const traceId = ($(this).attr("data-trace-id") || "").trim();
      if (traceId) {
        onOrderApprovalTraceLoad(traceId);
      }
    });
  },

  writeFilter(state) {
    // 해시/상태 복원 시 필터 UI를 동기화
    $("#country").val(state.country);
    $("#category").val(state.category);
    $("#sort").val(state.sort);
    $("#period").val(state.period);
    $("#viewLang").val(state.viewLang || "ko");
    $("#q").val(state.q || "");
  },

  readFilter() {
    // 현재 필터 입력값을 상태 저장 형식으로 반환
    return {
      country: $("#country").val(),
      category: $("#category").val(),
      sort: $("#sort").val(),
      period: $("#period").val(),
      viewLang: $("#viewLang").val() || "ko",
      q: $("#q").val() || ""
    };
  },

  renderLoading(categories, view, detailCategory) {
    // 섹션 개수에 맞춰 스켈레톤 카드 표시
    $("#retry").addClass("hide");
    const $grid = $("#portal-grid");
    $grid.removeClass("portal-masonry portal-detail");
    $grid.addClass(view === "detail" ? "portal-detail" : "portal-masonry");
    if (view === "detail" && detailCategory) {
      $("#status").text(`${store.labels[detailCategory]} (${detailCategory}) 상세 데이터를 불러오는 중...`);
    } else {
      $("#status").text("카테고리별 데이터를 불러오는 중...");
    }
    const skeleton = categories.map(() => '<div class="section-card"><div class="skeleton"></div></div>').join("");
    $grid.html(skeleton);
  },

  renderError(err) {
    const msg = err?.message || err?.code || "요청 실패";
    $("#status").text(`오류: ${msg}`);
    $("#retry").removeClass("hide");
  },

  setLiveState(text) {
    $("#live-state").text(text || "실시간 자동갱신 ON");
  },

  setActiveTab(tab) {
    const isHome = tab === "home";
    const isAssistant = tab === "assistant";
    const isAdmin = tab === "admin";
    $("#tab-home").toggleClass("active", isHome);
    $("#tab-assistant").toggleClass("active", isAssistant);
    $("#tab-admin").toggleClass("active", isAdmin);
    $("#home-view").toggleClass("hide", !isHome);
    $("#assistant-view").toggleClass("hide", !isAssistant);
    $("#admin-view").toggleClass("hide", !isAdmin);
  },

  renderSections(sections, traceId, state) {
    $("#retry").addClass("hide");
    const categoryOrder = state.category === "ALL" ? store.categories : [state.category];
    const totals = state.sectionTotals || {};
    const totalCount = categoryOrder.reduce((acc, category) => {
      const rows = sections[category] || [];
      const sectionTotal = Number(totals[category] ?? rows.length);
      return acc + sectionTotal;
    }, 0);

    if (state.view === "detail" && state.detailCategory) {
      const category = state.detailCategory;
      const rows = sections[category] || [];
      const sectionTotal = Number(totals[category] ?? rows.length);
      const pendingText = state.translationPending > 0 ? ` · 번역중 ${state.translationPending}건` : "";
      $("#status").text(`상세 ${store.labels[category]} (${category}) · 기사 ${sectionTotal}건${pendingText} · trace_id=${traceId || ""}`);
      $("#portal-grid")
        .removeClass("portal-masonry")
        .addClass("portal-detail")
        .html(this.renderDetailSection(category, rows, sectionTotal));
      this.bindThumbFallback();
      return;
    }

    const pendingText = state.translationPending > 0 ? ` · 번역중 ${state.translationPending}건` : "";
    $("#status").text(`섹션 ${categoryOrder.length}개 · 기사 ${totalCount}건${pendingText} · trace_id=${traceId || ""}`);

    const html = categoryOrder.map((category) => {
      const rows = sections[category] || [];
      const previewLimit = this.previewLimitFor(category);
      const previewRows = rows.slice(0, previewLimit);
      const sectionClass = `section-${category.toLowerCase()}`;
      const items = previewRows.length
        ? previewRows.map((item, idx) => this.renderItem(item, category === "BRK" && idx === 0)).join("")
        : `<div class="empty-box">${store.labels[category]} (${category}) 데이터 수집 전입니다. 관리자에서 수집 실행 가능합니다.</div>`;
      const sectionTotal = Number(totals[category] ?? rows.length);
      const moreCount = Math.max(sectionTotal - previewRows.length, 0);
      const moreButton = moreCount > 0
        ? `<button class="section-more-btn" type="button" data-category="${category}">+${moreCount} 더보기</button>`
        : "";

      return `
        <section class="section-card ${sectionClass}">
          <header class="section-head">
            <h2 class="section-title">${store.labels[category]} (${category})</h2>
            <div class="section-actions">
              <span class="section-count">${sectionTotal}건</span>
              ${moreButton}
            </div>
          </header>
          <div class="news-list">${items}</div>
        </section>
      `;
    }).join("");

    $("#portal-grid")
      .removeClass("portal-detail")
      .addClass("portal-masonry")
      .html(html);
    this.bindThumbFallback();
  },

  previewLimitFor(category) {
    if (category === "BRK") {
      return store.pageSize.brkHomePreview || store.pageSize.homePreview;
    }
    return store.pageSize.homePreview;
  },

  renderDetailSection(category, rows, sectionTotal) {
    const items = rows.length
      ? rows.map((item, idx) => this.renderItem(item, category === "BRK" && idx === 0)).join("")
      : `<div class="empty-box">${store.labels[category]} (${category}) 데이터 수집 전입니다. 관리자에서 수집 실행 가능합니다.</div>`;

    return `
      <section class="section-card section-detail">
        <header class="detail-head">
          <button id="detail-back" type="button">이전 화면</button>
          <h2 class="section-title">${store.labels[category]} (${category}) 상세</h2>
          <span class="section-count">${sectionTotal}건</span>
        </header>
        <div class="news-list detail-list">${items}</div>
      </section>
    `;
  },

  renderItem(item, isMain) {
    const pub = item.pub_utc ? new Date(item.pub_utc).toLocaleString() : "시간 미상";
    const evidenceEncoded = encodeURIComponent(JSON.stringify(item.evidence_spans || []));
    const countryLabel = this.countryLabel(item.country);
    const articleUrl = this.normalizeArticleUrl(item.url);
    const categoryCode = Array.isArray(item.category) && item.category.length ? item.category[0] : "";
    const categoryLabel = this.categoryLabel(categoryCode);
    const thumb = this.resolveThumbnail(item, categoryCode);
    const title = this.escapeHtml(item.title_ko || "(제목 없음)");
    const summary = this.escapeHtml(item.summary_ko || "(요약 없음)");
    const source = this.escapeHtml(item.source || "N/A");
    const trustText = this.formatTrustScore(item.trust_score);
    const thumbAlt = this.escapeHtml(`${source} 기사 썸네일`);
    const safeThumb = this.escapeAttr(thumb.primaryUrl);
    const safeThumbFallback = this.escapeAttr(thumb.fallbackUrl);
    const safeSourceIcon = this.escapeAttr(thumb.sourceIconUrl || "");
    const hasSourceIcon = safeSourceIcon.length > 0;

    return `
      <article class="news-item ${isMain ? "main" : ""}">
        <div class="news-topline">
          <span class="topline-category">${this.escapeHtml(categoryLabel)}</span>
          ${hasSourceIcon ? `<img class="topline-source-icon" src="${safeSourceIcon}" alt="${source} 아이콘" loading="lazy" />` : ""}
        </div>
        <div class="news-visual">
          <img class="news-thumb" src="${safeThumb}" data-fallback="${safeThumbFallback}" alt="${thumbAlt}" loading="lazy" />
        </div>
        <h3 class="news-title">
          ${articleUrl ? `<a href="${articleUrl}" target="_blank" rel="noopener noreferrer">${title}</a>` : title}
        </h3>
        <p class="news-summary">${summary}</p>
        <div class="news-meta">
          <span class="badge">국가 ${countryLabel}</span>
          <span class="badge">출처 ${source}</span>
          <span class="badge">신뢰도 ${trustText}</span>
          <span class="badge">발행 ${pub}</span>
          <button class="badge evidence-btn" data-evidence="${evidenceEncoded}">근거스팬</button>
        </div>
      </article>
    `;
  },

  bindThumbFallback() {
    // 원본 이미지 로드 실패 시 텍스트 기반 썸네일로 즉시 대체
    $(".news-thumb").on("error", function () {
      const fallback = $(this).attr("data-fallback");
      if (fallback && $(this).attr("src") !== fallback) {
        $(this).attr("src", fallback);
      }
    });
  },

  countryLabel(code) {
    // 한글(영문코드) 라벨로 표준화
    return store.countryLabels[code] || `${code || "N/A"}`;
  },

  categoryLabel(code) {
    if (!code) {
      return "미분류";
    }
    return store.labels[code] ? `${store.labels[code]} (${code})` : code;
  },

  normalizeArticleUrl(url) {
    if (!url) {
      return "";
    }
    return /^(https?:\/\/)/i.test(url) ? url : "";
  },

  resolveThumbnail(item, categoryCode) {
    const sourceIconUrl = this.isHttpUrl(item.source_icon_url)
      ? item.source_icon_url
      : this.buildFaviconUrl(item.url);
    const fallbackUrl = this.buildTextThumbnail(item, categoryCode);
    if (this.isHttpUrl(item.thumbnail_url)) {
      return {
        primaryUrl: item.thumbnail_url,
        fallbackUrl,
        sourceIconUrl
      };
    }
    return {
      primaryUrl: fallbackUrl,
      fallbackUrl,
      sourceIconUrl
    };
  },

  buildFaviconUrl(articleUrl) {
    const link = this.normalizeArticleUrl(articleUrl);
    if (!link) {
      return "";
    }
    return `https://www.google.com/s2/favicons?sz=128&domain_url=${encodeURIComponent(link)}`;
  },

  buildTextThumbnail(item, categoryCode) {
    const category = this.escapeXml(this.categoryLabel(categoryCode));
    const source = this.escapeXml(this.toPlainText(item.source || "출처 미상").slice(0, 22));
    const title = this.toPlainText(item.title_ko || item.title_raw || "제목 정보 없음");
    const titleLines = this.splitTitleLines(title, 22, 3)
      .map((line) => this.escapeXml(line));
    const line1 = titleLines[0] || "";
    const line2 = titleLines[1] || "";
    const line3 = titleLines[2] || "";

    const svg = `
      <svg xmlns="http://www.w3.org/2000/svg" width="640" height="360" viewBox="0 0 640 360">
        <defs>
          <linearGradient id="g" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0%" stop-color="#22487a" />
            <stop offset="100%" stop-color="#1b2f52" />
          </linearGradient>
        </defs>
        <rect width="640" height="360" fill="url(#g)" />
        <rect x="24" y="26" rx="16" ry="16" width="230" height="48" fill="rgba(255,255,255,0.18)" />
        <text x="42" y="58" font-size="24" fill="#ffffff" font-family="Noto Sans KR, sans-serif" font-weight="700">${category}</text>
        <rect x="24" y="92" rx="16" ry="16" width="592" height="194" fill="rgba(10,24,48,0.28)" />
        <text x="34" y="162" font-size="34" fill="#e8f1ff" font-family="Noto Sans KR, sans-serif" font-weight="700">${line1}</text>
        <text x="34" y="208" font-size="34" fill="#e8f1ff" font-family="Noto Sans KR, sans-serif" font-weight="700">${line2}</text>
        <text x="34" y="254" font-size="34" fill="#e8f1ff" font-family="Noto Sans KR, sans-serif" font-weight="700">${line3}</text>
        <rect x="24" y="286" rx="12" ry="12" width="300" height="42" fill="rgba(255,255,255,0.16)" />
        <text x="40" y="314" font-size="20" fill="#f5f9ff" font-family="Noto Sans KR, sans-serif">${source}</text>
      </svg>
    `.trim();

    return `data:image/svg+xml;charset=UTF-8,${encodeURIComponent(svg)}`;
  },

  splitTitleLines(text, lineLength, maxLines) {
    const normalized = this.toPlainText(text);
    if (!normalized) {
      return [];
    }
    const words = normalized.split(" ");
    const lines = [];
    let cursor = "";
    for (const word of words) {
      const candidate = cursor ? `${cursor} ${word}` : word;
      if (candidate.length > lineLength) {
        if (cursor) {
          lines.push(cursor);
          cursor = word;
        } else {
          lines.push(word.slice(0, lineLength));
          cursor = word.slice(lineLength);
        }
      } else {
        cursor = candidate;
      }
      if (lines.length >= maxLines) {
        break;
      }
    }
    if (cursor && lines.length < maxLines) {
      lines.push(cursor);
    }
    if (lines.length > maxLines) {
      lines.length = maxLines;
    }
    if (lines.length === maxLines) {
      const lastIndex = maxLines - 1;
      if (normalized.length > lines.join(" ").length) {
        lines[lastIndex] = `${lines[lastIndex].slice(0, Math.max(0, lineLength - 1))}...`;
      }
    }
    return lines;
  },

  renderStockSignals(signals, country, traceId) {
    const rows = Array.isArray(signals) ? signals : [];
    const countryLabel = this.countryLabel(country);
    $("#stock-meta").text(`${countryLabel} · ${rows.length}개 · trace_id=${traceId || ""}`);

    if (!rows.length) {
      $("#stock-list").html('<div class="empty-box">연관 뉴스가 부족해 주식 시그널을 계산하지 못했습니다.</div>');
      return;
    }

    const html = rows.map((row) => {
      const code = this.escapeHtml(row.stock_code || "-");
      const name = this.escapeHtml(row.stock_name || "-");
      const up = Number(row.up_probability ?? 50);
      const down = Number(row.down_probability ?? 50);
      const confidence = Number(row.confidence ?? 0);
      const reason = this.escapeHtml(row.reason || "근거 정보 없음");
      return `
        <article class="stock-card">
          <div class="stock-top">
            <span class="stock-name">${name}</span>
            <span class="stock-code">${code}</span>
          </div>
          <div class="stock-prob">
            <span class="stock-up">상승 ${up}%</span>
            <span class="stock-down">하락 ${down}%</span>
            <span class="badge">신뢰 ${confidence}</span>
          </div>
          <div class="stock-reason">${reason}</div>
        </article>
      `;
    }).join("");

    $("#stock-list").html(html);
  },

  renderStockSignalsError(err) {
    const msg = this.escapeHtml(err?.message || err?.code || "시그널 조회 실패");
    $("#stock-meta").text("시그널 오류");
    $("#stock-list").html(`<div class="empty-box">${msg}</div>`);
  },

  renderAnalysisPanels(state) {
    const scalp = Array.isArray(state.signalPanels?.scalp) ? state.signalPanels.scalp : [];
    const swing = Array.isArray(state.signalPanels?.swing) ? state.signalPanels.swing : [];
    const discovery = Array.isArray(state.signalPanels?.discovery) ? state.signalPanels.discovery : [];
    const position = state.signalPanels?.position;
    $("#analysis-meta").text(`trace_id=${state.analysisTraceId || ""}`);

    $("#panel-scalp").html(this.renderSignalItems(scalp, "단타 후보가 없습니다."));
    $("#panel-swing").html(this.renderSignalItems(swing, "중기 후보가 없습니다."));
    $("#panel-discovery").html(this.renderSignalItems(discovery, "발굴 후보가 없습니다."));
    $("#panel-position").html(
      position
        ? this.renderSignalItems([position], "포지션 시그널 없음")
        : '<div class="empty-box">포지션 시그널 없음</div>'
    );
  },

  renderSignalItems(rows, emptyText) {
    if (!rows.length) {
      return `<div class="empty-box">${this.escapeHtml(emptyText)}</div>`;
    }
    return rows.map((row) => {
      let breakdown = {};
      try {
        breakdown = row.probability_reason_breakdown_json ? JSON.parse(row.probability_reason_breakdown_json) : {};
      } catch (e) {
        breakdown = {};
      }
      const dataState = String(breakdown.data_state || "");
      const isDataGap = dataState === "NO_MATCHED_NEWS" || dataState === "INSUFFICIENT_DATA";
      const signalId = this.escapeAttr(row.signal_id || "");
      const name = this.escapeHtml(row.asset_name || row.asset_code || "-");
      const action = this.escapeHtml(row.action || "WATCH");
      const combined = Number(row.combined_confidence ?? 0).toFixed(3);
      const good = Number(row.good_news_probability ?? 0).toFixed(3);
      const bad = Number(row.bad_news_probability ?? 0).toFixed(3);
      const weekly = Number(row.weekly_context_score ?? 0).toFixed(3);
      const blocked = this.escapeHtml(row.blocked_reason || "");
      const purpose = this.escapeHtml(row.panel_purpose || "");
      const strategyKey = this.escapeHtml(row.strategy_key || "");
      const stateBadge = this.escapeHtml(row.state_badge || "");
      const stateReason = this.escapeHtml(row.state_reason || "");
      const recommendationState = this.escapeHtml(row.recommendation_state || "");
      const primaryMetricLabel = this.escapeHtml(row.primary_metric_label || "핵심지표");
      const primaryMetricValue = Number(row.primary_metric_value ?? 0).toFixed(3);
      const dedupApplied = Boolean(row.dedup_applied);
      const qualityDegraded = Boolean(row.quality_degraded);
      const badgeParts = [
        stateBadge ? `<span class="badge">${stateBadge}</span>` : "",
        recommendationState ? `<span class="badge">${recommendationState}</span>` : "",
        dedupApplied ? `<span class="badge">중복억제</span>` : "",
        qualityDegraded ? `<span class="badge">품질주의</span>` : "",
        isDataGap ? `<span class="badge">데이터부족</span>` : ""
      ].filter(Boolean).join("");
      const probabilityText = isDataGap
        ? `뉴스확률 보류 (${this.escapeHtml(dataState)})`
        : `${row.strategy_key === "SCALP" ? `호/악 ${good}/${bad}` : `주간 ${weekly}`}`;
      return `
        <article class="signal-item">
          <div class="signal-top">
            <span class="signal-name">${name}</span>
            <span class="signal-action">${action}</span>
          </div>
          ${(purpose || strategyKey) ? `<div class="signal-metrics"><span>${strategyKey || "STRATEGY"}</span><span>${purpose || "-"}</span>${badgeParts}</div>` : ""}
          <div class="signal-metrics">
            <span>${primaryMetricLabel} ${primaryMetricValue}</span>
            <span>결합 ${combined}</span>
            <span>${probabilityText}</span>
            ${blocked ? `<span>차단 ${blocked}</span>` : ""}
          </div>
          ${stateReason ? `<div class="signal-metrics"><span>${stateReason}</span></div>` : ""}
          ${signalId ? `<button class="signal-open-btn" type="button" data-signal-id="${signalId}">상세 보기</button>` : ""}
        </article>
      `;
    }).join("");
  },

  renderRiskPanel(risk, locks, backtestReport) {
    if (!risk) {
      $("#risk-meta").text("리스크 데이터 없음");
      $("#risk-summary").html('<div class="empty-box">리스크 데이터 없음</div>');
      $("#lock-list").html('<div class="empty-box">BUY_LOCK 없음</div>');
      return;
    }
    $("#risk-meta").text(`자본 ${Number(risk.capital_total ?? 0).toLocaleString()} · 몰빵 금지 정책 적용 중`);
    const items = [
      `투자금 ${Number(risk.capital_total ?? 0).toLocaleString()}`,
      `투자중 ${Number(risk.invested_amount ?? 0).toLocaleString()} / 현금 ${Number(risk.cash_remaining ?? 0).toLocaleString()}`,
      `종목당 최대 ${(Number(risk.max_position_ratio_per_asset ?? 0) * 100).toFixed(1)}%`,
      `테마당 최대 ${(Number(risk.max_theme_exposure_ratio ?? 0) * 100).toFixed(1)}%`,
      `국가당 최대 ${(Number(risk.max_country_exposure_ratio ?? 0) * 100).toFixed(1)}%`,
      `동시 보유 최대 ${Number(risk.max_open_positions ?? 0)}종목`,
      `익절 ${Number(risk.take_profit_pct ?? 0)}% / 손절 ${Number(risk.stop_loss_pct ?? 0)}%`
    ];
    const strategyActionDistribution = risk.strategy_action_distribution || {};
    const strategyBlockedCount = risk.strategy_blocked_count || {};
    const blockedReasonDistribution = risk.blocked_reason_distribution || {};
    const duplicateExposureStats = risk.duplicate_exposure_stats || {};
    const strategySummary = Object.entries(strategyActionDistribution).map(([strategy, dist]) => {
      const buy = Number(dist?.BUY_CANDIDATE || 0);
      const sell = Number(dist?.SELL_CANDIDATE || 0);
      const watch = Number(dist?.WATCH || 0);
      const blocked = Number(strategyBlockedCount?.[strategy] || 0);
      return `${strategy}: 추천(BUY/SELL) ${buy + sell}건, WATCH ${watch}건, 차단 ${blocked}건`;
    });
    if (strategySummary.length) {
      items.push(...strategySummary.slice(0, 4));
    }
    items.push(`중복노출 통계: 자산중복 ${Number(duplicateExposureStats.duplicate_asset_rows || 0)}건 / 자산+윈도우중복 ${Number(duplicateExposureStats.duplicate_asset_window_rows || 0)}건`);
    const topBlockedReasons = Object.entries(blockedReasonDistribution)
      .sort((a, b) => Number(b[1] || 0) - Number(a[1] || 0))
      .slice(0, 3)
      .map(([reason, count]) => `${reason}:${count}`);
    if (topBlockedReasons.length) {
      items.push(`차단 사유 상위: ${topBlockedReasons.join(", ")}`);
    }
    items.push(`BUY_LOCK ${Number(risk.buy_lock_count || 0)}건 / 재분석 대기 ${Number(risk.reanalysis_pending_count || 0)}건`);
    const degradedAssets = Array.isArray(risk.data_quality_degraded_assets) ? risk.data_quality_degraded_assets : [];
    if (degradedAssets.length) {
      items.push(`품질저하 종목 ${degradedAssets.length}건 (상위: ${degradedAssets.slice(0, 2).join(" | ")})`);
    }
    const positionWarnings = Array.isArray(risk.position_limit_warning_assets) ? risk.position_limit_warning_assets : [];
    if (positionWarnings.length) {
      items.push(`종목 비중 경고 ${positionWarnings.length}건 (상위: ${positionWarnings.slice(0, 2).join(" | ")})`);
    }
    const divWarnings = Array.isArray(risk.portfolio_diversification_warnings) ? risk.portfolio_diversification_warnings : [];
    if (divWarnings.length) {
      items.push(`분산 경고: ${divWarnings.slice(0, 2).join(" / ")}`);
    }
    if (risk.reference_only) {
      items.push(
        `참고용 제안(실주문 아님): 기준자금 ${Number(risk.reference_capital_basis || 0).toLocaleString()}, 1회 진입 ${Number(risk.recommended_entry_ratio_pct || 0).toFixed(1)}% (~${Number(risk.recommended_entry_amount || 0).toLocaleString()}), 분할매수 ${JSON.stringify(risk.recommended_buy_split_ratios || [])}, 재분석락 ${Number(risk.reanalysis_lock_minutes || 0)}분`
      );
    }
    if (backtestReport) {
      items.push(`백테스트 요약: ${backtestReport.summary || "-"}`);
      items.push(`정책 전/후 MDD: ${Number(backtestReport.before_policy_mdd ?? 0).toFixed(4)} -> ${Number(backtestReport.after_policy_mdd ?? 0).toFixed(4)}`);
    }
    $("#risk-summary").html(items.map((item) => `<div class="risk-item">${this.escapeHtml(item)}</div>`).join(""));

    const lockRows = Array.isArray(locks) ? locks : [];
    if (!lockRows.length) {
      $("#lock-list").html('<div class="empty-box">현재 BUY_LOCK 종목이 없습니다.</div>');
      return;
    }
    $("#lock-list").html(lockRows.map((row) => {
      const asset = this.escapeHtml(row.asset_name || row.asset_code || "-");
      const reason = this.escapeHtml(row.lock_reason || "-");
      const until = row.lock_until ? new Date(row.lock_until).toLocaleString() : "미정";
      return `<div class="lock-item">${asset} · 사유 ${reason} · 해제예정 ${until}</div>`;
    }).join(""));
  },

  renderAssistantLoading() {
    $("#assistant-meta").text("AI 비서 대시보드를 불러오는 중...");
    $("#assistant-status-bar").html('<div class="empty-box">상태 수집 중...</div>');
    $("#assistant-watch-meta").text("종목 로딩 중");
    $("#assistant-watchlist").html('<div class="empty-box">관심종목 데이터를 준비 중입니다...</div>');
    $("#assistant-strategy-meta").text("전략 데이터 로딩 중");
    $("#assistant-strategy-tabs").html("");
    $("#assistant-strategy-list").html('<div class="empty-box">전략 후보를 계산 중입니다...</div>');
    $("#assistant-risk-meta").text("리스크 정책 로딩 중");
    $("#assistant-risk-panel").html('<div class="empty-box">리스크/자금관리 데이터를 준비 중입니다...</div>');
    $("#assistant-detail-meta").text("상세 대기");
    $("#assistant-detail-panel").html('<div class="empty-box">종목을 선택하면 뉴스/가격/리스크 근거를 표시합니다.</div>');
  },

  renderAssistantError(err) {
    const msg = this.escapeHtml(err?.message || err?.code || "AI 비서 대시보드 조회 실패");
    $("#assistant-meta").text(`오류: ${msg}`);
    $("#assistant-status-bar").html(`<div class="empty-box">${msg}</div>`);
  },

  renderAssistantDashboard(state) {
    const dashboard = state?.assistantDashboard || null;
    if (!dashboard) {
      this.renderAssistantLoading();
      return;
    }

    const statusBar = dashboard.status_bar || {};
    const watchlist = Array.isArray(dashboard.watchlist) ? dashboard.watchlist : [];
    const strategyOrder = Array.isArray(dashboard.strategy_order) ? dashboard.strategy_order : ["SCALP", "SWING", "CHART_RESPONSE", "DISCOVERY"];
    const selectedStrategy = state.assistantSelectedStrategy || strategyOrder[0] || "SCALP";
    const strategies = dashboard.strategies || {};
    const selectedStrategyPayload = strategies[selectedStrategy] || {};
    const selectedRows = Array.isArray(selectedStrategyPayload.items) ? selectedStrategyPayload.items : [];
    const selectedSignalId = state.assistantSelectedSignalId || dashboard.selected_signal_id || "";
    const providerStatus = statusBar.provider_status || {};
    const collectionStatus = statusBar.data_collection_status || {};
    const ragRuntime = statusBar.rag_runtime || {};
    const warnings = Array.isArray(statusBar.warnings) ? statusBar.warnings : [];

    $("#assistant-meta").text(
      `${this.countryLabel(dashboard.country || state.country)} · trace_id=${state.assistantTraceId || ""} · ${new Date().toLocaleString()}`
    );
    $("#assistant-status-bar").html(this.renderAssistantStatusBar(statusBar, providerStatus, collectionStatus, ragRuntime, warnings));

    $("#assistant-watch-meta").text(`관심종목 ${watchlist.length}개`);
    $("#assistant-watchlist").html(
      watchlist.length
        ? watchlist.map((row) => this.renderAssistantWatchItem(row, selectedSignalId)).join("")
        : '<div class="empty-box">추천/관심 종목이 없습니다. 데이터 수집 상태 또는 토글 설정을 확인하세요.</div>'
    );

    $("#assistant-strategy-meta").text(
      `${this.escapeHtml(store.strategyLabels?.[selectedStrategy] || selectedStrategy)} · ${selectedRows.length}개`
    );
    $("#assistant-strategy-tabs").html(
      strategyOrder.map((key) => {
        const strategy = strategies[key] || {};
        const rows = Array.isArray(strategy.items) ? strategy.items : [];
        const label = strategy.label || store.strategyLabels?.[key] || key;
        return `<button class="assistant-strategy-tab ${key === selectedStrategy ? "active" : ""}" type="button" data-strategy="${this.escapeAttr(key)}">${this.escapeHtml(label)} <span>${rows.length}</span></button>`;
      }).join("")
    );
    $("#assistant-strategy-list").html(
      selectedRows.length
        ? selectedRows.map((row) => this.renderAssistantStrategyRow(row, selectedSignalId)).join("")
        : '<div class="empty-box">선택한 전략의 후보가 없습니다.</div>'
    );

    const risk = dashboard.risk_panel || null;
    const locks = Array.isArray(dashboard.locks) ? dashboard.locks : [];
    $("#assistant-risk-meta").text(`모의/참고용 정책 · BUY_LOCK ${locks.length}건`);
    $("#assistant-risk-panel").html(this.renderAssistantRiskPanel(risk, locks));
  },

  renderAssistantStatusBar(statusBar, providerStatus, collectionStatus, ragRuntime, warnings) {
    const providerHealth = providerStatus.provider_health || {};
    const providerKeys = Object.keys(providerHealth);
    const providerText = providerKeys.length
      ? providerKeys.map((key) => {
        const item = providerHealth[key] || {};
        const status = item.status || "UNKNOWN";
        return `${key}:${status}`;
      }).join(" | ")
      : "provider 정보 없음";

    const chips = [
      `<div class="assistant-status-chip"><span>시장상태</span><strong>${this.escapeHtml(statusBar.market_state || "UNKNOWN")}</strong></div>`,
      `<div class="assistant-status-chip"><span>Provider</span><strong>${this.escapeHtml(providerText)}</strong></div>`,
      `<div class="assistant-status-chip"><span>수집상태</span><strong>${this.escapeHtml(collectionStatus.status || "UNKNOWN")}</strong></div>`,
      `<div class="assistant-status-chip"><span>자동분석</span><strong>${statusBar.auto_analysis_enabled ? "ON" : "OFF"}</strong></div>`,
      `<div class="assistant-status-chip"><span>주문모드</span><strong>${this.escapeHtml(statusBar.mode_label || "PAPER_ONLY")}</strong></div>`,
      `<div class="assistant-status-chip"><span>RAG</span><strong>성공률 ${this.pct(ragRuntime.success_rate)} / fallback ${Number(ragRuntime.fallback_count || 0)}</strong></div>`,
      `<div class="assistant-status-chip"><span>지연</span><strong>${this.num(providerStatus.avg_latency_ms, 0)}ms / RAG ${this.num(ragRuntime.avg_latency_ms, 0)}ms</strong></div>`
    ];

    const warningRows = warnings.length
      ? `<div class="assistant-warning-list">${warnings.map((msg) => `<div class="assistant-warning-item">${this.escapeHtml(msg)}</div>`).join("")}</div>`
      : "";

    return `${chips.join("")}${warningRows}`;
  },

  renderAssistantWatchItem(row, selectedSignalId) {
    const signalId = this.escapeAttr(row.signal_id || "");
    const assetCode = this.escapeAttr(row.asset_code || "");
    const selected = signalId && signalId === String(selectedSignalId || "");
    const lastPrice = row.last_price == null ? "-" : Number(row.last_price).toLocaleString();
    const changePct = row.change_pct == null ? null : Number(row.change_pct) * 100;
    const changeClass = changePct == null ? "" : (changePct > 0 ? "up" : (changePct < 0 ? "down" : "flat"));
    const volumeText = row.volume == null ? "-" : Number(row.volume).toLocaleString();
    const confidence = Number(row.combined_confidence ?? 0).toFixed(3);
    const newsCount = Number(row.news_count_24h || 0);
    const riskBadge = this.escapeHtml(row.risk_badge || "정상");
    const stateBadge = this.escapeHtml(row.state_badge || row.recommendation_state || "-");
    const action = this.escapeHtml(row.action || "WATCH");
    const dataState = this.escapeHtml(row.data_state || "");
    const provider = this.escapeHtml(row.quote_provider || "-");
    const quoteAge = row.quote_age_seconds == null ? "-" : `${Math.max(0, Number(row.quote_age_seconds || 0))}s`;
    return `
      <button type="button" class="assistant-watch-item ${selected ? "selected" : ""}" data-signal-id="${signalId}" data-asset-code="${assetCode}">
        <div class="assistant-watch-top">
          <span class="assistant-watch-name">${this.escapeHtml(row.asset_name || row.asset_code || "-")}</span>
          <span class="assistant-watch-action">${action}</span>
        </div>
        <div class="assistant-watch-price">
          <span class="price">${lastPrice}</span>
          <span class="change ${changeClass}">${changePct == null ? "-" : `${changePct.toFixed(2)}%`}</span>
        </div>
        <div class="assistant-watch-meta-line">
          <span class="badge">${this.escapeHtml(row.strategy_key || "-")}</span>
          <span class="badge">${stateBadge}</span>
          <span class="badge">${riskBadge}</span>
          ${dataState ? `<span class="badge">상태 ${dataState}</span>` : ""}
        </div>
        <div class="assistant-watch-meta-line">
          <span>거래량 ${this.escapeHtml(volumeText)}</span>
          <span>뉴스 ${newsCount}건</span>
          <span>신뢰 ${confidence}</span>
        </div>
        <div class="assistant-watch-meta-line">
          <span>provider ${provider}</span>
          <span>시세나이 ${quoteAge}</span>
        </div>
        <div class="assistant-watch-reason">${this.escapeHtml(row.recommendation_basis_text || row.news_basis_text || "-")}</div>
      </button>
    `;
  },

  renderAssistantStrategyRow(row, selectedSignalId) {
    const signalId = this.escapeAttr(row.signal_id || "");
    const assetCode = this.escapeAttr(row.asset_code || "");
    const selected = signalId && signalId === String(selectedSignalId || "");
    const stateBadge = this.escapeHtml(row.state_badge || row.recommendation_state || "-");
    const blocked = this.escapeHtml(row.blocked_reason || "");
    const quality = Boolean(row.quality_degraded);
    let breakdown = {};
    try {
      breakdown = row.probability_reason_breakdown_json ? JSON.parse(row.probability_reason_breakdown_json) : {};
    } catch (e) {
      breakdown = {};
    }
    const dataState = this.escapeHtml(String(breakdown.data_state || ""));
    const primaryMetricValue = Number(row.primary_metric_value ?? 0).toFixed(3);
    const metricLine = row.strategy_key === "SCALP"
      ? `호/악 ${Number(row.good_news_probability ?? 0).toFixed(3)} / ${Number(row.bad_news_probability ?? 0).toFixed(3)}`
      : `결합 ${Number(row.combined_confidence ?? 0).toFixed(3)} · 주간 ${Number(row.weekly_context_score ?? 0).toFixed(3)}`;
    return `
      <button type="button" class="assistant-signal-row ${selected ? "selected" : ""}" data-signal-id="${signalId}" data-asset-code="${assetCode}">
        <div class="assistant-signal-row-top">
          <span class="name">${this.escapeHtml(row.asset_name || row.asset_code || "-")}</span>
          <span class="action">${this.escapeHtml(row.action || "WATCH")}</span>
        </div>
        <div class="assistant-signal-row-badges">
          <span class="badge">${this.escapeHtml(row.strategy_key || "-")}</span>
          <span class="badge">${stateBadge}</span>
          ${quality ? '<span class="badge">품질주의</span>' : ""}
          ${Boolean(row.dedup_applied) ? '<span class="badge">중복억제</span>' : ""}
          ${dataState ? `<span class="badge">상태 ${dataState}</span>` : ""}
        </div>
        <div class="assistant-signal-row-desc">${this.escapeHtml(row.panel_purpose || row.state_reason || "-")}</div>
        <div class="assistant-signal-row-desc">${this.escapeHtml(row.primary_metric_label || "핵심지표")} ${primaryMetricValue} · ${this.escapeHtml(metricLine)}</div>
        ${blocked ? `<div class="assistant-signal-row-desc warn">차단사유: ${blocked}</div>` : ""}
      </button>
    `;
  },

  renderAssistantRiskPanel(risk, locks) {
    if (!risk) {
      return '<div class="empty-box">리스크/자금관리 데이터 없음</div>';
    }
    const summaryLines = [
      `기준자금 ${Number(risk.reference_capital_basis || risk.capital_total || 0).toLocaleString()} (참고용)`,
      `1회 진입 ${Number(risk.recommended_entry_ratio_pct || 0).toFixed(1)}% / 약 ${Number(risk.recommended_entry_amount || 0).toLocaleString()}`,
      `분할매수 비중 ${this.escapeHtml(JSON.stringify(risk.recommended_buy_split_ratios || []))}`,
      `익절 ${Number(risk.take_profit_pct || 0)}% / 손절 ${Number(risk.stop_loss_pct || 0)}%`,
      `재분석 락 ${Number(risk.reanalysis_lock_minutes || 0)}분`,
      `종목당 최대 ${(Number(risk.max_position_ratio_per_asset || 0) * 100).toFixed(1)}%`,
      `국가당 최대 ${(Number(risk.max_country_exposure_ratio || 0) * 100).toFixed(1)}% / 테마당 최대 ${(Number(risk.max_theme_exposure_ratio || 0) * 100).toFixed(1)}%`
    ];
    const warnings = [
      ...(Array.isArray(risk.portfolio_diversification_warnings) ? risk.portfolio_diversification_warnings : []),
      ...(Array.isArray(risk.position_limit_warning_assets) ? risk.position_limit_warning_assets.map((v) => `비중경고 ${v}`) : []),
      ...(Array.isArray(risk.data_quality_degraded_assets) ? risk.data_quality_degraded_assets.map((v) => `품질저하 ${v}`) : [])
    ];

    const lockLines = (Array.isArray(locks) ? locks : []).slice(0, 6).map((row) => {
      const asset = this.escapeHtml(row.asset_name || row.asset_code || "-");
      const reason = this.escapeHtml(row.lock_reason || "-");
      const until = row.lock_until ? new Date(row.lock_until).toLocaleString() : "미정";
      return `<div class="assistant-sub-row">${asset} · ${reason} · ${until}</div>`;
    });

    return `
      <div class="assistant-sub-section">
        ${summaryLines.map((line) => `<div class="assistant-sub-row">${this.escapeHtml(line)}</div>`).join("")}
      </div>
      <div class="assistant-sub-section">
        <div class="assistant-sub-title">경고/차단 신호</div>
        ${warnings.length
          ? warnings.slice(0, 8).map((line) => `<div class="assistant-sub-row warn">${this.escapeHtml(line)}</div>`).join("")
          : '<div class="assistant-sub-row">현재 주요 경고 없음</div>'}
      </div>
      <div class="assistant-sub-section">
        <div class="assistant-sub-title">BUY_LOCK / 재분석 대기</div>
        ${lockLines.length ? lockLines.join("") : '<div class="assistant-sub-row">현재 BUY_LOCK 없음</div>'}
      </div>
    `;
  },

  renderAssistantDetailLoading(signalId) {
    $("#assistant-detail-meta").text(`상세 로딩 중 · signal_id=${signalId || ""}`);
    $("#assistant-detail-panel").html('<div class="empty-box">종목 상세(뉴스/가격/리스크 근거)를 불러오는 중...</div>');
  },

  renderAssistantDetailError(err) {
    const msg = this.escapeHtml(err?.message || err?.code || "상세 조회 실패");
    $("#assistant-detail-meta").text(`상세 오류: ${msg}`);
    $("#assistant-detail-panel").html(`<div class="empty-box">${msg}</div>`);
  },

  renderAssistantDetail(detail, dashboard, traceId = "") {
    if (!detail) {
      $("#assistant-detail-meta").text("종목 상세 대기");
      $("#assistant-detail-panel").html('<div class="empty-box">관심종목 또는 전략 후보를 선택하면 상세 근거를 표시합니다.</div>');
      return;
    }
    let breakdown = {};
    try {
      breakdown = detail.probability_reason_breakdown_json ? JSON.parse(detail.probability_reason_breakdown_json) : {};
    } catch (e) {
      breakdown = {};
    }
    let ragRefs = [];
    try {
      ragRefs = detail.rag_context_refs_json ? JSON.parse(detail.rag_context_refs_json) : [];
    } catch (e) {
      ragRefs = [];
    }
    const assistant = detail.assistant_rag || {};
    const newsEvidence = Array.isArray(detail.news_evidence) ? detail.news_evidence : [];
    const priceEvidence = Array.isArray(detail.price_evidence) ? detail.price_evidence : [];
    const riskEvidence = Array.isArray(detail.risk_evidence) ? detail.risk_evidence : [];
    const missing = Array.isArray(detail.missing_requirements) ? detail.missing_requirements : [];
    const changes = Array.isArray(detail.change_conditions) ? detail.change_conditions : [];
    const risk = dashboard?.risk_panel || {};
    const dataState = this.escapeHtml(String(breakdown.data_state || ""));
    const uncertainty = Number(breakdown.uncertainty_score ?? 0);
    const helper = [
      `권장 1회 진입 ${Number(risk.recommended_entry_ratio_pct || 0).toFixed(1)}%`,
      `분할매수 ${this.escapeHtml(JSON.stringify(risk.recommended_buy_split_ratios || []))}`,
      `익절 ${Number(risk.take_profit_pct || 0)}%`,
      `손절 ${Number(risk.stop_loss_pct || 0)}%`,
      `재분석 락 ${Number(risk.reanalysis_lock_minutes || 0)}분`
    ];

    $("#assistant-detail-meta").text(
      `${this.escapeHtml(detail.asset_name || detail.asset_code || "-")} · ${this.escapeHtml(detail.action || "WATCH")} · trace_id=${this.escapeHtml(traceId || "")}`
    );
    $("#assistant-detail-panel").html(`
      <div class="assistant-detail-grid">
        <section class="assistant-detail-card">
          <h4>추천/확률 요약</h4>
          <div class="assistant-detail-line">결정: ${this.escapeHtml(detail.action || "WATCH")} / 결합신뢰 ${Number(detail.combined_confidence ?? 0).toFixed(3)}</div>
          <div class="assistant-detail-line">호재 ${Number(detail.good_news_probability ?? 0).toFixed(3)} / 악재 ${Number(detail.bad_news_probability ?? 0).toFixed(3)} / 불확실성 ${uncertainty.toFixed(3)}</div>
          <div class="assistant-detail-line">상태: ${dataState || "-"} ${detail.blocked_reason ? `· 차단사유 ${this.escapeHtml(detail.blocked_reason)}` : ""}</div>
          <div class="assistant-detail-line">왜 ${this.escapeHtml(detail.action || "WATCH")}인가: ${this.escapeHtml(detail.decision_why || detail.explain_text || "-")}</div>
        </section>
        <section class="assistant-detail-card">
          <h4>뉴스 근거</h4>
          ${newsEvidence.length ? newsEvidence.slice(0, 8).map((line) => `<div class="assistant-detail-line">${this.escapeHtml(line)}</div>`).join("") : '<div class="assistant-detail-line">뉴스 근거 없음</div>'}
        </section>
        <section class="assistant-detail-card">
          <h4>가격 근거</h4>
          ${priceEvidence.length ? priceEvidence.slice(0, 8).map((line) => `<div class="assistant-detail-line">${this.escapeHtml(line)}</div>`).join("") : '<div class="assistant-detail-line">가격 근거 없음</div>'}
        </section>
        <section class="assistant-detail-card">
          <h4>리스크 근거</h4>
          ${riskEvidence.length ? riskEvidence.slice(0, 8).map((line) => `<div class="assistant-detail-line">${this.escapeHtml(line)}</div>`).join("") : '<div class="assistant-detail-line">리스크 근거 없음</div>'}
        </section>
        <section class="assistant-detail-card">
          <h4>무엇이 부족한지 / 바뀔 조건</h4>
          <div class="assistant-detail-line">부족요건: ${this.escapeHtml(missing.join(" / ") || "-")}</div>
          <div class="assistant-detail-line">변경조건: ${this.escapeHtml(changes.join(" / ") || "-")}</div>
        </section>
        <section class="assistant-detail-card">
          <h4>참고용 진입/익절/손절 (실주문 아님)</h4>
          ${helper.map((line) => `<div class="assistant-detail-line">${line}</div>`).join("")}
        </section>
        <section class="assistant-detail-card">
          <h4>RAG 보조설명</h4>
          <div class="assistant-detail-line">source=${this.escapeHtml(assistant.source || "-")} · fallback=${Boolean(assistant.fallback_applied)} · applied=${Boolean(assistant.applied)}</div>
          <div class="assistant-detail-line">${this.escapeHtml(assistant.summary || "비활성/없음")}</div>
          <div class="assistant-detail-line">주의: ${this.escapeHtml((assistant.caution_bullets || []).join(" / ") || "-")}</div>
          <div class="assistant-detail-line">근거요약: ${this.escapeHtml((assistant.evidence_bullets || []).join(" / ") || "-")}</div>
        </section>
        <section class="assistant-detail-card">
          <h4>trace/근거 링크</h4>
          <div class="assistant-detail-line">trace_id: ${this.escapeHtml(traceId || "")}</div>
          <div class="assistant-detail-line">rag_context_refs: ${Array.isArray(ragRefs) ? ragRefs.length : 0}건</div>
          <div class="assistant-detail-line">AI 비서 질의응답 영역은 다음 차수에서 연결 예정(현재는 근거 기반 요약만 제공)</div>
        </section>
      </div>
    `);
  },

  renderSignalDetailModal(detail) {
    if (!detail) {
      return;
    }
    const pressure = detail.pressure_analysis || {};
    const riskChecks = Array.isArray(detail.risk_checks) ? detail.risk_checks : [];
    const missingRequirements = Array.isArray(detail.missing_requirements) ? detail.missing_requirements : [];
    const changeConditions = Array.isArray(detail.change_conditions) ? detail.change_conditions : [];
    let scalpBreakdown = {};
    try {
      scalpBreakdown = detail.probability_reason_breakdown_json ? JSON.parse(detail.probability_reason_breakdown_json) : {};
    } catch (e) {
      scalpBreakdown = {};
    }
    const dataState = String(scalpBreakdown.data_state || "");
    const assistant = detail.assistant_rag || {};
    const assistantEvidence = Array.isArray(assistant.evidence_bullets) ? assistant.evidence_bullets : [];
    const assistantCautions = Array.isArray(assistant.caution_bullets) ? assistant.caution_bullets : [];
    const assistantMissing = Array.isArray(assistant.missing_data_bullets) ? assistant.missing_data_bullets : [];
    const assistantChanges = Array.isArray(assistant.change_triggers) ? assistant.change_triggers : [];
    const probabilitySummary = (dataState === "NO_MATCHED_NEWS" || dataState === "INSUFFICIENT_DATA")
      ? `뉴스기반 확률 보류 (${dataState})`
      : `호재확률 ${Number(detail.good_news_probability ?? 0).toFixed(3)} / 악재확률 ${Number(detail.bad_news_probability ?? 0).toFixed(3)}`;
    const body = `
      <div class="signal-detail-grid">
        <section class="signal-detail-card"><strong>${this.escapeHtml(detail.asset_name || detail.asset_code || "-")}</strong> · ${this.escapeHtml(detail.action || "WATCH")}</section>
        <section class="signal-detail-card">${this.escapeHtml(probabilitySummary)}</section>
        <section class="signal-detail-card">주간컨텍스트 ${Number(detail.weekly_context_score ?? 0).toFixed(3)} / 결합신뢰 ${Number(detail.combined_confidence ?? 0).toFixed(3)}</section>
        <section class="signal-detail-card">판단 사유: ${this.escapeHtml(detail.decision_why || detail.explain_text || "-")}</section>
        <section class="signal-detail-card">부족한 점: ${this.escapeHtml(missingRequirements.join(" / ") || "-")}</section>
        <section class="signal-detail-card">변경 조건: ${this.escapeHtml(changeConditions.join(" / ") || "-")}</section>
        <section class="signal-detail-card">압력분석: sell_detected=${Boolean(pressure.sell_pressure_detected)} / sell_negative=${Boolean(pressure.sell_pressure_is_negative)} / buy_detected=${Boolean(pressure.buy_pressure_detected)} / buy_positive=${Boolean(pressure.buy_pressure_is_positive)} / volume_same=${Boolean(pressure.volume_regime_same)}</section>
        <section class="signal-detail-card">리스크체크: ${this.escapeHtml(riskChecks.join(", ") || "-")}</section>
        <section class="signal-detail-card">주문 차단사유: ${this.escapeHtml(detail.blocked_reason || "없음")}</section>
        <section class="signal-detail-card">RAG 보조요약(${this.escapeHtml(assistant.source || "N/A")}): ${this.escapeHtml(assistant.summary || "비활성/없음")}</section>
        <section class="signal-detail-card">RAG 근거요약: ${this.escapeHtml(assistantEvidence.join(" / ") || "-")}</section>
        <section class="signal-detail-card">RAG 주의점: ${this.escapeHtml(assistantCautions.join(" / ") || "-")}</section>
        <section class="signal-detail-card">RAG 추가조건: ${this.escapeHtml([...assistantMissing, ...assistantChanges].join(" / ") || "-")}</section>
      </div>
    `;
    $("#signal-modal-body").html(body);
    $("#signal-modal").removeClass("hide");
  },

  closeSignalModal() {
    $("#signal-modal").addClass("hide");
    $("#signal-modal-body").empty();
  },

  renderAdminLoading() {
    $("#admin-meta").text("진단 데이터를 불러오는 중...");
    const loading = '<div class="empty-box">진단 데이터를 조회 중입니다...</div>';
    [
      "#admin-market-summary",
      "#admin-quality-score",
      "#admin-market-gaps",
      "#admin-provider-audit",
      "#admin-universe",
      "#admin-mapping",
      "#admin-signals",
      "#admin-toggles",
      "#admin-order-approvals",
      "#admin-order-approval-detail"
    ].forEach((selector) => $(selector).html(loading));
  },

  renderAdminError(err) {
    const msg = this.escapeHtml(err?.message || err?.code || "관리자 진단 요청 실패");
    $("#admin-meta").text(`오류: ${msg}`);
    $("#admin-trace-detail").html(`<div class="empty-box">${msg}</div>`);
  },

  renderAdminDashboard(diag) {
    if (!diag) {
      return;
    }
    const summary = diag.marketSummary?.data || {};
    const quality = diag.qualityScore?.data || {};
    const gaps = diag.marketGaps?.data || {};
    const providerAudit = diag.providerAudit?.data || {};
    const universe = diag.universe?.data || {};
    const diversity = diag.universeDiversity?.data || {};
    const tickerAlias = diag.tickerAlias?.data || {};
    const mapping = diag.mapping?.data || {};
    const signalAudit = diag.signalAudit?.data || {};
    const signalAlignment = diag.signalAlignment?.data || {};
    const signalConfidence = diag.signalConfidence?.data || {};
    const ragSummary = signalConfidence.assistant_rag_summary || {};
    const toggles = Array.isArray(diag.featureToggles?.data) ? diag.featureToggles.data : [];

    $("#admin-meta").text(`생성 ${new Date().toLocaleString()} · trace_id=${diag.marketSummary?.trace_id || ""}`);

    $("#admin-market-summary").html(`
      <div class="admin-kv">평균 품질점수 ${this.num(summary.avg_quality_score, 2)}</div>
      <div class="admin-kv">누락률 ${this.pct(summary.avg_missing_rate)}</div>
      <div class="admin-kv">지연률 ${this.pct(summary.avg_delay_rate)}</div>
      <div class="admin-kv">미해결 갭 ${Number(summary.unresolved_gap_count || 0)}건</div>
      <div class="admin-kv">스냅샷 ${Number(summary.snapshot_count || 0)}건</div>
    `);

    const qualityTrend = Array.isArray(quality.trend) ? quality.trend.slice(0, 8) : [];
    $("#admin-quality-score").html(qualityTrend.length
      ? qualityTrend.map((row) => `
          <div class="admin-row">
            <span>${this.escapeHtml(row.bucket_hour_utc || "-")}</span>
            <span>점수 ${this.num(row.avg_quality_score, 2)}</span>
            <span>누락 ${this.pct(row.avg_missing_rate)}</span>
          </div>
        `).join("")
      : '<div class="empty-box">품질 추이 데이터 없음</div>');

    const gapItems = Array.isArray(gaps.items) ? gaps.items.slice(0, 10) : [];
    $("#admin-market-gaps").html(gapItems.length
      ? gapItems.map((row) => `
          <div class="admin-row">
            <span>[${this.escapeHtml(row.severity || "")}] ${this.escapeHtml(row.event_type || "")}</span>
            <span>${this.escapeHtml(row.asset_code || "-")}</span>
            <span>${this.escapeHtml(row.event_time_utc || "-")}</span>
          </div>
        `).join("")
      : '<div class="empty-box">갭 이벤트 없음</div>');

    const providerItems = Array.isArray(providerAudit.items) ? providerAudit.items.slice(0, 8) : [];
    $("#admin-provider-audit").html(`
      <div class="admin-kv">성공률 ${this.pct(providerAudit.success_rate)}</div>
      <div class="admin-kv">평균 지연 ${this.num(providerAudit.avg_latency_ms, 2)} ms</div>
      <div class="admin-kv">실패 ${Number(providerAudit.failed_count || 0)}건</div>
      ${providerItems.map((row) => `
        <div class="admin-row">
          <span>${this.escapeHtml(row.provider || "-")} / ${this.escapeHtml(row.api_name || "-")}</span>
          <span>${row.success ? "SUCCESS" : "FAIL"}</span>
          <span>${this.num(row.latency_ms, 0)}ms</span>
        </div>
      `).join("")}
    `);

    $("#admin-universe").html(`
      <div class="admin-kv">자산 ${Number(universe.total_assets || 0)}개 / 핵심 ${Number(universe.core_assets || 0)}개</div>
      <div class="admin-kv">패밀리 집중도 ${this.pct(diversity.family_concentration_ratio)}</div>
      <div class="admin-kv">다양성 경고 ${diversity.diversity_warning ? "예" : "아니오"}</div>
      <div class="admin-kv">상위 패밀리 ${this.escapeHtml(JSON.stringify(diversity.top_repeated_families || {}))}</div>
    `);

    $("#admin-mapping").html(`
      <div class="admin-kv">티커 실패 ${Number(tickerAlias.failed_rows || 0)}건</div>
      <div class="admin-kv">매핑 오탐률 ${this.pct(mapping.latest?.theme_match_false_positive_rate)}</div>
      <div class="admin-kv">과확장률 ${this.pct(mapping.latest?.country_theme_overexpansion_rate)}</div>
      <div class="admin-kv">샘플 ${Number(mapping.latest?.sample_size || 0)}건</div>
    `);

    const signalItems = Array.isArray(signalAudit.items) ? signalAudit.items.slice(0, 6) : [];
    const signalByStrategy = signalConfidence.action_distribution_by_strategy || {};
    const duplicateSignalStats = signalConfidence.duplicate_exposure_stats || {};
    $("#admin-signals").html(`
      <div class="admin-kv">감사 로그 ${Number(signalAudit.count || 0)}건 / 차단 ${Number(signalAudit.blocked_count || 0)}건</div>
      <div class="admin-kv">미래데이터 차단 ${Number(signalAlignment.future_data_blocked_count || 0)}건</div>
      <div class="admin-kv">번역지연 ${Number(signalAlignment.translation_delayed_count || 0)}건</div>
      <div class="admin-kv">저신뢰 시그널 ${Number(signalConfidence.low_confidence_count || 0)}건</div>
      <div class="admin-kv">전략별 액션 분포 ${this.escapeHtml(JSON.stringify(signalByStrategy))}</div>
      <div class="admin-kv">중복노출 통계 ${this.escapeHtml(JSON.stringify(duplicateSignalStats))}</div>
      <div class="admin-kv">RAG 성공률 ${this.pct(ragSummary.success_rate)} / fallback ${Number(ragSummary.fallback_count || 0)} / timeout ${Number(ragSummary.timeout_count || 0)} / 평균지연 ${this.num(ragSummary.avg_latency_ms, 2)}ms</div>
      ${signalItems.map((row) => `
        <div class="admin-row">
          <span>${this.escapeHtml(row.asset_code || "-")}</span>
          <span>${this.escapeHtml(row.engine_type || "-")}</span>
          <span>${this.escapeHtml(row.decision_after_risk || "-")}</span>
        </div>
      `).join("")}
    `);

    $("#admin-toggles").html(toggles.length
      ? toggles.map((row) => {
        const featureKey = String(row.feature_key || "").toUpperCase();
        const description = store.featureToggleDescriptions?.[featureKey] || "설명 미등록";
        const scopeText = `${row.scope_type || "GLOBAL"}:${row.scope_value || "*"}`;
        const risky = featureKey === "LIVE_TRADE" || featureKey === "AUTO_ORDER_FULLY_AUTOMATED";
        const reason = row.reason || "-";
        const updatedBy = row.updated_by || "-";
        const updatedAt = row.updated_at ? new Date(row.updated_at).toLocaleString() : "-";
        return `
          <div class="admin-toggle-item ${risky ? "danger" : ""}">
            <div class="admin-toggle-head">
              <span class="admin-toggle-key">${this.escapeHtml(featureKey)}</span>
              <span class="admin-toggle-state ${row.enabled ? "on" : "off"}">${row.enabled ? "ON" : "OFF"}</span>
            </div>
            <div class="admin-toggle-desc">${this.escapeHtml(description)}</div>
            <div class="admin-toggle-meta">범위 ${this.escapeHtml(scopeText)} · 변경자 ${this.escapeHtml(updatedBy)} · 변경시각 ${this.escapeHtml(updatedAt)}</div>
            <div class="admin-toggle-meta">사유 ${this.escapeHtml(reason)}</div>
            ${risky ? '<div class="admin-toggle-warning">위험 토글입니다. 운영 승인 절차 없이 활성화하지 마세요.</div>' : ""}
            <button class="toggle-patch-btn" type="button" data-id="${row.id}" data-enabled="${!row.enabled}">${row.enabled ? "OFF로 변경" : "ON으로 변경"}</button>
          </div>
        `;
      }).join("")
      : '<div class="empty-box">등록된 기능 토글이 없습니다.</div>');

    this.renderAdminOrderApprovalPanel(store.state.adminOrderApprovals, store.state.adminOrderApprovalDetail, store.state.adminOrderApprovalTrace);
  },

  renderAdminOrderApprovalPanel(queueEnvelope, detailEnvelope, traceEnvelope) {
    const queue = queueEnvelope?.data || {};
    const items = Array.isArray(queue.items) ? queue.items : [];
    const listHtml = items.length
      ? items.slice(0, 20).map((row) => {
        const stage = String(row.current_stage || "");
        const traceId = String(row.trace_id || "");
        const canApprove = stage === "RECOMMEND" || stage === "APPROVE";
        const canReject = stage !== "REJECTED" && stage !== "ORDER_EXECUTED";
        const canOrder = stage === "APPROVE" || stage === "ORDER_FAILED";
        return `
          <div class="admin-toggle-item">
            <div class="admin-toggle-head">
              <span class="admin-toggle-key">${this.escapeHtml(row.asset_name || row.asset_code || "-")}</span>
              <span class="admin-toggle-state ${stage === "ORDER_EXECUTED" ? "on" : "off"}">${this.escapeHtml(stage || "-")}</span>
            </div>
            <div class="admin-toggle-meta">workflow #${row.id} · ${this.escapeHtml(row.order_side || "-")} · 시그널 ${this.escapeHtml(row.signal_id || "-")}</div>
            <div class="admin-toggle-meta">사유 ${this.escapeHtml(row.recommendation_reason || "-")}</div>
            <div class="admin-toggle-meta">trace ${this.escapeHtml(traceId || "-")}</div>
            <div class="admin-row" style="gap:6px;flex-wrap:wrap;">
              <button type="button" class="order-approval-detail-btn" data-workflow-id="${row.id}">상세</button>
              ${canApprove ? `<button type="button" class="order-approval-approve-btn" data-workflow-id="${row.id}">승인</button>` : ""}
              ${canReject ? `<button type="button" class="order-approval-reject-btn" data-workflow-id="${row.id}">반려</button>` : ""}
              ${canOrder ? `<button type="button" class="order-approval-order-btn" data-workflow-id="${row.id}">모의주문 요청</button>` : ""}
              ${traceId ? `<button type="button" class="order-approval-trace-btn" data-trace-id="${this.escapeAttr(traceId)}">trace 조회</button>` : ""}
            </div>
          </div>
        `;
      }).join("")
      : '<div class="empty-box">추천/승인 큐가 비어 있습니다.</div>';

    $("#admin-order-approvals").html(`
      <div class="admin-kv">큐 건수 ${Number(queue.count || 0)}건 / stage_counts ${this.escapeHtml(JSON.stringify(queue.stage_counts || {}))}</div>
      ${listHtml}
    `);

    if (!detailEnvelope?.data) {
      $("#admin-order-approval-detail").html('<div class="empty-box">워크플로 상세를 선택하면 근거/스냅샷/이벤트를 표시합니다.</div>');
      return;
    }

    const detail = detailEnvelope.data || {};
    const workflow = detail.workflow || {};
    const snapshots = detail.snapshots || {};
    const events = Array.isArray(detail.events) ? detail.events.slice(0, 15) : [];
    const paperOrder = detail.paper_order || {};
    const refs = Array.isArray(snapshots.news_context_refs) ? snapshots.news_context_refs : [];
    const traceSummary = traceEnvelope?.data || {};

    $("#admin-order-approval-detail").html(`
      <div class="admin-kv">선택 workflow #${Number(workflow.id || 0)} · ${this.escapeHtml(workflow.current_stage || "-")} · ${this.escapeHtml(workflow.asset_name || workflow.asset_code || "-")}</div>
      <div class="admin-kv">추천액션 ${this.escapeHtml(workflow.recommended_action || "-")} / 주문방향 ${this.escapeHtml(workflow.order_side || "-")} / 신뢰도 ${this.num(workflow.recommendation_confidence, 4)}</div>
      <div class="admin-kv">승인자 ${this.escapeHtml(workflow.approved_by || "-")} · 반려자 ${this.escapeHtml(workflow.rejected_by || "-")} · trace ${this.escapeHtml(workflow.trace_id || "-")}</div>
      <div class="admin-kv">실행모드 ${this.escapeHtml(workflow.order_execution_mode || "-")} · 실주문요청 ${Boolean(workflow.live_trade_requested)} · 차단사유 ${this.escapeHtml(workflow.live_trade_blocked_reason || "-")}</div>
      <div class="admin-kv">스냅샷 signal/quote/risk/assistant/newsRefs = ${Object.keys(snapshots.signal || {}).length}/${Object.keys(snapshots.quote || {}).length}/${Object.keys(snapshots.risk || {}).length}/${Object.keys(snapshots.assistant || {}).length}/${refs.length}</div>
      <div class="admin-kv">paper_order ${paperOrder.id ? `#${paperOrder.id} ${this.escapeHtml(paperOrder.status || "-")} ${this.escapeHtml(paperOrder.blocked_reason || "")}` : "없음"}</div>
      <div class="admin-kv">trace_summary workflows=${Number(traceSummary.workflow_count || 0)} / events=${Number(traceSummary.event_count || 0)}</div>
      <div class="admin-trace-sections">
        <section>
          <h4>주문 승인 이벤트 로그</h4>
          ${events.length ? events.map((row) => `<div class="admin-row"><span>${this.escapeHtml(row.event_type || "-")} ${this.escapeHtml(row.from_stage || "-")}→${this.escapeHtml(row.to_stage || "-")}</span><span>${row.success ? "OK" : "FAIL"}</span><span>${this.escapeHtml(row.created_at || "-")}</span></div>`).join("") : '<div class="empty-box">이벤트 로그 없음</div>'}
        </section>
        <section>
          <h4>근거 스냅샷 요약</h4>
          <div class="admin-kv">signal: ${this.escapeHtml(JSON.stringify(snapshots.signal || {}))}</div>
          <div class="admin-kv">quote: ${this.escapeHtml(JSON.stringify(snapshots.quote || {}))}</div>
          <div class="admin-kv">assistant: ${this.escapeHtml(JSON.stringify(snapshots.assistant || {}))}</div>
        </section>
      </div>
    `);
  },

  renderAdminTraceDetail(traceDetail) {
    if (!traceDetail || !traceDetail.data) {
      $("#admin-trace-detail").html('<div class="empty-box">trace 상세 데이터 없음</div>');
      return;
    }
    const data = traceDetail.data;
    const assistant = data.assistant_summary || {};
    const assistantHighlights = Array.isArray(assistant.highlights) ? assistant.highlights : [];
    const assistantCautions = Array.isArray(assistant.cautions) ? assistant.cautions : [];
    const counts = data.counts || {};
    const marketCollection = data.market_collection || {};
    const signals = data.signals || {};
    const qualityRows = Array.isArray(marketCollection.quality_snapshots) ? marketCollection.quality_snapshots.slice(0, 5) : [];
    const gapRows = Array.isArray(marketCollection.gap_events) ? marketCollection.gap_events.slice(0, 5) : [];
    const providerRows = Array.isArray(marketCollection.provider_audits) ? marketCollection.provider_audits.slice(0, 5) : [];
    const strategyRuns = Array.isArray(signals.strategy_runs) ? signals.strategy_runs.slice(0, 5) : [];
    const signalAudits = Array.isArray(signals.signal_audits) ? signals.signal_audits.slice(0, 5) : [];
    const assistantAudits = Array.isArray(signals.assistant_rag_audits) ? signals.assistant_rag_audits.slice(0, 5) : [];
    $("#admin-trace-detail").html(`
      <div class="admin-kv">trace_id ${this.escapeHtml(data.trace_id || "")}</div>
      <div class="admin-kv">counts ${this.escapeHtml(JSON.stringify(counts))}</div>
      <div class="admin-kv">feature_toggles ${Array.isArray(data.feature_toggles) ? data.feature_toggles.length : 0}건</div>
      <div class="admin-kv">assistant_summary ${this.escapeHtml(assistant.summary || "없음")}</div>
      <div class="admin-kv">assistant_source ${this.escapeHtml(assistant.source || "-")} / fallback=${Boolean(assistant.fallback_applied)} / latency=${this.num(assistant.latency_ms_total, 0)}ms</div>
      <div class="admin-kv">assistant_highlights ${this.escapeHtml(assistantHighlights.join(" | ") || "-")}</div>
      <div class="admin-kv">assistant_cautions ${this.escapeHtml(assistantCautions.join(" | ") || "-")}</div>
      <div class="admin-trace-sections">
        <section>
          <h4>수집 품질/Provider</h4>
          ${qualityRows.map((row) => `<div class="admin-row"><span>${this.escapeHtml(row.provider || "-")} ${this.escapeHtml(row.country || "-")}</span><span>품질 ${this.num(row.quality_score, 2)}</span><span>${this.escapeHtml(row.snapshot_time_utc || "-")}</span></div>`).join("") || '<div class="empty-box">품질 스냅샷 없음</div>'}
          ${gapRows.map((row) => `<div class="admin-row"><span>[${this.escapeHtml(row.severity || "-")}] ${this.escapeHtml(row.event_type || "-")}</span><span>${this.escapeHtml(row.asset_code || "-")}</span><span>${this.escapeHtml(row.event_time_utc || "-")}</span></div>`).join("") || ""}
          ${providerRows.map((row) => `<div class="admin-row"><span>${this.escapeHtml(row.provider || "-")} / ${this.escapeHtml(row.api_name || "-")}</span><span>${row.success ? "OK" : "FAIL"}</span><span>${this.num(row.latency_ms, 0)}ms</span></div>`).join("") || ""}
        </section>
        <section>
          <h4>시그널/전략 실행</h4>
          ${strategyRuns.map((row) => `<div class="admin-row"><span>${this.escapeHtml(row.run_type || "-")} / ${this.escapeHtml(row.status || "-")}</span><span>처리 ${Number(row.processed_count || 0)}</span><span>생성 ${Number(row.created_signal_count || 0)}</span></div>`).join("") || '<div class="empty-box">전략 실행 로그 없음</div>'}
          ${signalAudits.map((row) => `<div class="admin-row"><span>${this.escapeHtml(row.asset_code || "-")} / ${this.escapeHtml(row.engine_type || "-")}</span><span>${this.escapeHtml(row.blocked_reason || "-")}</span><span>${this.escapeHtml(row.signal_id || "-")}</span></div>`).join("") || ""}
        </section>
        <section>
          <h4>RAG 감사 로그</h4>
          ${assistantAudits.map((row) => `<div class="admin-row"><span>${this.escapeHtml(row.request_scope || "-")} / ${this.escapeHtml(row.request_key || "-")}</span><span>fallback=${Boolean(row.fallback_applied)}</span><span>${this.escapeHtml(row.error_code || "-")} · ${this.num(row.latency_ms_total, 0)}ms</span></div>`).join("") || '<div class="empty-box">RAG 감사 로그 없음</div>'}
        </section>
      </div>
    `);
  },

  num(value, scale = 2) {
    const n = Number(value);
    if (!Number.isFinite(n)) {
      return "0";
    }
    return n.toFixed(scale);
  },

  pct(value) {
    const n = Number(value);
    if (!Number.isFinite(n)) {
      return "0.00%";
    }
    return `${(n * 100).toFixed(2)}%`;
  },

  formatTrustScore(rawValue) {
    const value = Number(rawValue);
    if (!Number.isFinite(value)) {
      return "0";
    }
    return value.toFixed(2).replace(/\.00$/, "");
  },

  toPlainText(value) {
    return String(value || "")
      .replaceAll(/<[^>]+>/g, " ")
      .replaceAll(/\s+/g, " ")
      .trim();
  },

  isHttpUrl(url) {
    return typeof url === "string" && /^(https?:\/\/)/i.test(url);
  },

  escapeXml(value) {
    return String(value || "")
      .replaceAll("&", "&amp;")
      .replaceAll("<", "&lt;")
      .replaceAll(">", "&gt;")
      .replaceAll('"', "&quot;")
      .replaceAll("'", "&#39;");
  },

  escapeAttr(value) {
    return this.escapeHtml(String(value || "")).replaceAll("\n", " ");
  },

  escapeHtml(value) {
    // XSS 방지를 위한 최소 HTML 이스케이프
    return String(value)
      .replaceAll("&", "&amp;")
      .replaceAll("<", "&lt;")
      .replaceAll(">", "&gt;")
      .replaceAll('"', "&quot;")
      .replaceAll("'", "&#39;");
  }
};
