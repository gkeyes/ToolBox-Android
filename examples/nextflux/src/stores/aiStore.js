import { atom } from "nanostores";

// summary state per article id: { [articleId]: { loading, summary, error } }
export const aiSummaries = atom({});

export const setSummaryLoading = (articleId) => {
  aiSummaries.set({
    ...aiSummaries.get(),
    [articleId]: { loading: true, summary: "", error: null },
  });
};

export const appendSummaryChunk = (articleId, chunk) => {
  const current = aiSummaries.get();
  const prev = current[articleId];
  if (!prev) return;
  aiSummaries.set({
    ...current,
    [articleId]: { ...prev, summary: (prev.summary || "") + chunk },
  });
};

export const setSummaryDone = (articleId) => {
  const current = aiSummaries.get();
  const prev = current[articleId];
  if (!prev) return;
  aiSummaries.set({
    ...current,
    [articleId]: { ...prev, loading: false },
  });
};

export const setSummaryError = (articleId, error) => {
  aiSummaries.set({
    ...aiSummaries.get(),
    [articleId]: { loading: false, summary: null, error },
  });
};

export const clearSummary = (articleId) => {
  const current = { ...aiSummaries.get() };
  delete current[articleId];
  aiSummaries.set(current);
};

// A cancelled stream is not an error. A user-stopped summary keeps the text it
// already has; one abandoned by switching articles (or with no text) is removed
// so the entry neither lingers as "loading" nor shows a stale error.
export const finishCancelledSummary = (articleId, { keepPartial = false } = {}) => {
  const prev = aiSummaries.get()[articleId];
  if (!prev) return;
  if (keepPartial && prev.summary) setSummaryDone(articleId);
  else clearSummary(articleId);
};

export const clearAllSummaries = () => aiSummaries.set({});
