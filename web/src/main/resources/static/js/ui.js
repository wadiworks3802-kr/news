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
    onAdminRefresh,
    onAdminTraceLoad,
    onToggleUpsert,
    onTogglePatch
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
    $("#tab-admin").on("click", () => onTabChange("admin"));
    $("#admin-refresh").on("click", onAdminRefresh);
    $("#admin-trace-load").on("click", onAdminTraceLoad);
    $("#toggle-upsert").on("click", onToggleUpsert);
    $(document).on("click", ".toggle-patch-btn", function () {
      const id = $(this).attr("data-id");
      const enabled = $(this).attr("data-enabled");
      if (id) {
        onTogglePatch(id, enabled === "true");
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
    const isAdmin = tab === "admin";
    $("#tab-home").toggleClass("active", !isAdmin);
    $("#tab-admin").toggleClass("active", isAdmin);
    $("#home-view").toggleClass("hide", isAdmin);
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
      const signalId = this.escapeAttr(row.signal_id || "");
      const name = this.escapeHtml(row.asset_name || row.asset_code || "-");
      const action = this.escapeHtml(row.action || "WATCH");
      const combined = Number(row.combined_confidence ?? 0).toFixed(3);
      const good = Number(row.good_news_probability ?? 0).toFixed(3);
      const bad = Number(row.bad_news_probability ?? 0).toFixed(3);
      const weekly = Number(row.weekly_context_score ?? 0).toFixed(3);
      const blocked = this.escapeHtml(row.blocked_reason || "");
      return `
        <article class="signal-item">
          <div class="signal-top">
            <span class="signal-name">${name}</span>
            <span class="signal-action">${action}</span>
          </div>
          <div class="signal-metrics">
            <span>결합 ${combined}</span>
            <span>호재 ${good}</span>
            <span>악재 ${bad}</span>
            <span>주간 ${weekly}</span>
            ${blocked ? `<span>차단 ${blocked}</span>` : ""}
          </div>
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
      "#admin-toggles"
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
    $("#admin-signals").html(`
      <div class="admin-kv">감사 로그 ${Number(signalAudit.count || 0)}건 / 차단 ${Number(signalAudit.blocked_count || 0)}건</div>
      <div class="admin-kv">미래데이터 차단 ${Number(signalAlignment.future_data_blocked_count || 0)}건</div>
      <div class="admin-kv">번역지연 ${Number(signalAlignment.translation_delayed_count || 0)}건</div>
      <div class="admin-kv">저신뢰 시그널 ${Number(signalConfidence.low_confidence_count || 0)}건</div>
      ${signalItems.map((row) => `
        <div class="admin-row">
          <span>${this.escapeHtml(row.asset_code || "-")}</span>
          <span>${this.escapeHtml(row.engine_type || "-")}</span>
          <span>${this.escapeHtml(row.decision_after_risk || "-")}</span>
        </div>
      `).join("")}
    `);

    $("#admin-toggles").html(toggles.length
      ? toggles.map((row) => `
          <div class="admin-row">
            <span>${this.escapeHtml(row.feature_key || "-")} (${this.escapeHtml(row.scope_type || "GLOBAL")}:${this.escapeHtml(row.scope_value || "*")})</span>
            <span>${row.enabled ? "ON" : "OFF"}</span>
            <button class="toggle-patch-btn" type="button" data-id="${row.id}" data-enabled="${!row.enabled}">${row.enabled ? "OFF로 변경" : "ON으로 변경"}</button>
          </div>
        `).join("")
      : '<div class="empty-box">등록된 기능 토글이 없습니다.</div>');
  },

  renderAdminTraceDetail(traceDetail) {
    if (!traceDetail || !traceDetail.data) {
      $("#admin-trace-detail").html('<div class="empty-box">trace 상세 데이터 없음</div>');
      return;
    }
    const data = traceDetail.data;
    $("#admin-trace-detail").html(`
      <div class="admin-kv">trace_id ${this.escapeHtml(data.trace_id || "")}</div>
      <div class="admin-kv">counts ${this.escapeHtml(JSON.stringify(data.counts || {}))}</div>
      <div class="admin-kv">feature_toggles ${Array.isArray(data.feature_toggles) ? data.feature_toggles.length : 0}건</div>
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
