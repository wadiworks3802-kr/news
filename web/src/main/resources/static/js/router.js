/**
 * router 스크립트.
 *
 * 프로젝트 기능 구현을 위한 핵심 컴포넌트.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
window.router = {
  fromHash() {
    const hash = location.hash.replace(/^#/, "");
    const params = new URLSearchParams(hash);
    const detailCategory = params.get("detailCategory") || null;
    return {
      tab: params.get("tab") || null,
      country: params.get("country") || null,
      category: params.get("category") || null,
      sort: params.get("sort") || null,
      period: params.get("period") || null,
      viewLang: params.get("viewLang") || null,
      q: params.get("q") || null,
      view: params.get("view") || null,
      detailCategory: detailCategory
    };
  },
  push(state) {
    const params = new URLSearchParams({
      tab: state.activeTab || "home",
      country: state.country,
      category: state.category,
      sort: state.sort,
      period: state.period,
      viewLang: state.viewLang || "ko",
      q: state.q || "",
      view: state.view || "home",
      detailCategory: state.detailCategory || ""
    });
    const nextHash = params.toString();
    if (location.hash.replace(/^#/, "") !== nextHash) {
      location.hash = nextHash;
      return true;
    }
    return false;
  }
};
