/*
 * 3차 뉴스 썸네일 파싱/저장/진단 가시성 강화:
 * - 뉴스 썸네일 URL/출처/상태를 구조화 저장
 * - 파싱 실패/빈값/기본 placeholder fallback 원인 분리 진단 기반
 */

ALTER TABLE news
    ADD COLUMN IF NOT EXISTS thumbnail_url TEXT,
    ADD COLUMN IF NOT EXISTS thumbnail_source VARCHAR(16),
    ADD COLUMN IF NOT EXISTS thumbnail_status VARCHAR(16);

COMMENT ON COLUMN news.thumbnail_url IS '뉴스 카드 대표 썸네일 URL (RSS/OG/TWITTER/BODY 추출 결과)';
COMMENT ON COLUMN news.thumbnail_source IS '썸네일 URL 추출 출처 (RSS/OG/TWITTER/BODY/DEFAULT)';
COMMENT ON COLUMN news.thumbnail_status IS '썸네일 추출 상태 (SUCCESS/EMPTY/FAILED)';
