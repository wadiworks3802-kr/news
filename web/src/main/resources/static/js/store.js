/**
 * 프론트 전역 상태 저장소.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 *
 * 국가/카테고리 라벨, 화면 상태, 섹션 데이터의 단일 소스 역할을 한다.
 */
window.store = {
  categories: ["BRK", "POL", "ECO", "MKT", "DEV", "IND"],
  autoRefreshSeconds: 30,
  pageSize: {
    homePreview: 3,
    brkHomePreview: 4,
    // 홈은 preview 기준 최소 조회만 수행하고, 더보기 개수는 meta.total로 계산
    homeFetch: 4,
    detail: 20
  },
  countryLabels: {
    KR: "대한민국 (KR)",
    US: "미국 (US)",
    UK: "영국 (UK)",
    DE: "독일 (DE)",
    FR: "프랑스 (FR)",
    JP: "일본 (JP)",
    CN: "중국 (CN)",
    IN: "인도 (IN)",
    RU: "러시아 (RU)",
    BR: "브라질 (BR)"
  },
  labels: {
    BRK: "속보",
    POL: "정치",
    ECO: "경제",
    MKT: "시장",
    DEV: "기술",
    IND: "산업"
  },
  strategyLabels: {
    SCALP: "단타",
    SWING: "중기",
    CHART_RESPONSE: "차트대응",
    DISCOVERY: "6개월발굴"
  },
  featureToggleDescriptions: {
    SIGNAL_GENERATION: "시그널 엔진 전체 생성 스위치",
    SCALP_ENGINE: "단타 전략 엔진 활성화",
    SWING_ENGINE: "중기 전략 엔진 활성화",
    POSITION_ENGINE: "차트 대응 전략 엔진 활성화",
    DISCOVERY_ENGINE: "발굴 전략 엔진 활성화",
    RAG_ASSISTANT: "RAG/경량모델 보조설명 계층 활성화",
    AUTO_ORDER_WITH_ADMIN_APPROVAL: "관리자 승인 후 모의주문 자동 요청 허용 (실주문 아님)",
    LIVE_TRADE: "실주문 관련 기능 사용 가능 여부 (기본 OFF)",
    AUTO_ORDER_FULLY_AUTOMATED: "완전자동 주문 파이프라인 사용 여부 (고위험)"
  },
  state: {
    activeTab: "home",
    country: "KR",
    category: "ALL",
    sort: "latest",
    period: "24h",
    viewLang: "ko",
    page: 1,
    q: "",
    view: "home",
    detailCategory: "",
    loading: false,
    error: null,
    sections: {},
    sectionTotals: {},
    traceId: "",
    stockSignals: [],
    stockTraceId: "",
    stockError: null,
    autoRefreshEnabled: true,
    signalPanels: {
      scalp: [],
      swing: [],
      discovery: [],
      position: null
    },
    riskPanel: null,
    lockPanel: [],
    signalDetail: null,
    backtestReport: null,
    analysisTraceId: "",
    analysisError: null,
    adminApiKey: "",
    adminDiagnostics: null,
    adminTraceDetail: null,
    adminOrderApprovals: null,
    adminOrderApprovalDetail: null,
    adminOrderApprovalTrace: null,
    adminError: null,
    assistantDashboard: null,
    assistantTraceId: "",
    assistantError: null,
    assistantSelectedStrategy: "SCALP",
    assistantSelectedSignalId: "",
    assistantSelectedAssetCode: "",
    assistantDetail: null,
    assistantDetailTraceId: "",
    assistantDetailLoading: false,
    assistantQaQuestion: "",
    assistantQaAnswer: null,
    assistantQaTraceId: "",
    assistantQaLoading: false,
    assistantQaError: null
  },
  set(next) {
    // 기존 상태를 유지한 채 전달된 값만 병합 갱신
    this.state = { ...this.state, ...next };
  }
};
