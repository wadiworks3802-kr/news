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
    adminError: null
  },
  set(next) {
    // 기존 상태를 유지한 채 전달된 값만 병합 갱신
    this.state = { ...this.state, ...next };
  }
};
