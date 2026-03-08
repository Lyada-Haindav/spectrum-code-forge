const STORAGE_KEY = "code-forge-draft";

const form = document.querySelector("#builder-form");
const loadDemoButton = document.querySelector("#load-demo");
const clearFormButton = document.querySelector("#clear-form");
const toggleInputButton = document.querySelector("#toggle-input");
const generateButton = document.querySelector("#generate-button");
const messageBanner = document.querySelector("#message-banner");
const emptyState = document.querySelector("#empty-state");
const loadingState = document.querySelector("#loading-state");
const loadingTitle = document.querySelector("#loading-title");
const loadingCopy = document.querySelector("#loading-copy");
const loadingSteps = document.querySelector("#loading-steps");
const resultView = document.querySelector("#result-view");
const inputSummary = document.querySelector("#input-summary");
const planTitle = document.querySelector("#plan-title");
const planCopy = document.querySelector("#plan-copy");
const planBadge = document.querySelector("#plan-badge");
const upgradeButton = document.querySelector("#upgrade-button");
const helperChips = document.querySelectorAll("[data-helper]");
const historyGuest = document.querySelector("#history-guest");
const historyEmpty = document.querySelector("#history-empty");
const historyList = document.querySelector("#history-list");
const historyRefreshButton = document.querySelector("#history-refresh");
const historySearch = document.querySelector("#history-search");
const historySummary = document.querySelector("#history-summary");
const submitHint = document.querySelector("#submit-hint");
const emptyStateTitle = document.querySelector("#empty-state-title");
const emptyStateCopy = document.querySelector("#empty-state-copy");
const premiumModal = document.querySelector("#premium-modal");
const premiumCloseButton = document.querySelector("#premium-close");
const premiumMessage = document.querySelector("#premium-message");
const premiumPlanOptions = document.querySelector("#premium-plan-options");
const premiumPlanLabel = document.querySelector("#premium-plan-label");
const premiumPrice = document.querySelector("#premium-price");
const premiumDuration = document.querySelector("#premium-duration");
const premiumUpiId = document.querySelector("#premium-upi-id");
const premiumNote = document.querySelector("#premium-note");
const premiumPayLink = document.querySelector("#premium-pay-link");
const premiumCopyUpi = document.querySelector("#premium-copy-upi");
const premiumForm = document.querySelector("#premium-form");
const premiumReferenceInput = document.querySelector("#premium-reference");
const premiumSubmitButton = document.querySelector("#premium-submit");

const LOADING_STAGES = [
  {
    title: "Reading the constraints",
    copy: "Scanning the limits, examples, and required output format."
  },
  {
    title: "Choosing the algorithm",
    copy: "Filtering out slower approaches and locking the practical one."
  },
  {
    title: "Writing the solution",
    copy: "Building the main code, variants, and focused tests."
  },
  {
    title: "Polishing the answer",
    copy: "Structuring the final explanation so it is easier to revise."
  }
];

const DEMO_DATA = {
  problemStatement:
    "You are given an array of n integers and an integer k. Count the number of subarrays whose sum is divisible by k.\n\nInput:\nThe first line contains n and k.\nThe second line contains n integers.\n\nOutput:\nPrint the number of subarrays whose sum is divisible by k.",
  constraints:
    "1 <= n <= 2 * 10^5\n1 <= k <= 10^5\n-10^9 <= a[i] <= 10^9",
  examples:
    "Input:\n5 5\n4 5 0 -2 -3\n\nOutput:\n6",
  primaryLanguage: "Java",
  alternateLanguages: "C++, Python",
  solutionMode: "logic_and_code",
  interfaceStyle: "competitive_programming",
  explanationDepth: "balanced",
  additionalRequirements:
    "Use fast input handling and explain why prefix remainder counting works."
};

const state = {
  latestResult: null,
  codeBlocks: [],
  selectedCodeIndex: 0,
  pendingRequestController: null,
  isInputCollapsed: false,
  historyItems: [],
  activeHistoryId: null,
  user: null,
  isLoading: false,
  historyQuery: "",
  loadingStageIndex: 0,
  loadingTimer: null,
  billingCheckout: null,
  selectedPremiumPlanCode: "",
  isPremiumSubmitting: false
};

bootstrap();

function bootstrap() {
  hydrateDraft();

  form.addEventListener("submit", handleSubmit);
  form.addEventListener("input", persistDraft);
  loadDemoButton.addEventListener("click", loadDemo);
  clearFormButton.addEventListener("click", clearForm);
  toggleInputButton.addEventListener("click", toggleInputPanel);
  resultView.addEventListener("click", handleResultActions);
  historyList.addEventListener("click", handleHistoryActions);
  historyRefreshButton.addEventListener("click", refreshHistory);
  historySearch.addEventListener("input", handleHistorySearch);
  window.addEventListener("solver-auth-changed", handleAuthChange);
  upgradeButton.addEventListener("click", handleUpgradeClick);

  if (premiumCloseButton) {
    premiumCloseButton.addEventListener("click", closePremiumModal);
  }

  if (premiumModal) {
    premiumModal.addEventListener("click", (event) => {
      if (event.target === premiumModal) {
        closePremiumModal();
      }
    });
  }

  if (premiumForm) {
    premiumForm.addEventListener("submit", handlePremiumSubmit);
  }

  if (premiumCopyUpi) {
    premiumCopyUpi.addEventListener("click", copyPremiumUpiId);
  }

  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape" && premiumModal && !premiumModal.hidden) {
      closePremiumModal();
    }
  });

  for (const chip of helperChips) {
    chip.addEventListener("click", () => appendRequirement(chip.dataset.helper || ""));
  }

  updateInputView();
  renderHistory();
  renderPlanState();
  updateWorkspaceAccessState();
  handleAuthChange({
    detail: {
      user: window.solverAuth?.getUser?.() || null
    }
  });
}

async function handleSubmit(event) {
  event.preventDefault();
  const payload = getFormData();
  await runGeneration(payload);
}

async function runGeneration(payload) {
  hideMessage();

  if (!payload.problemStatement.trim()) {
    showMessage("Add a problem statement first.");
    return;
  }

  if (!state.user) {
    showMessage("Sign in first to unlock code output.");
    window.solverAuth?.open?.("signin");
    updateWorkspaceAccessState();
    return;
  }

  if (!state.user.emailVerified) {
    showMessage("Verify your email first. Check your inbox or resend the confirmation email.");
    updateWorkspaceAccessState();
    return;
  }

  if (!state.user.premium && Number(state.user.dailyRemaining || 0) <= 0) {
    showMessage("Free plan limit reached for today. Upgrade to premium for unlimited solves.");
    await openPremiumModal();
    updateWorkspaceAccessState();
    return;
  }

  persistDraft();
  setLoading(true);
  resultView.innerHTML = "";

  try {
    if (state.pendingRequestController) {
      state.pendingRequestController.abort();
    }

    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 40000);
    state.pendingRequestController = controller;

    const response = await fetch("/api/generate", {
      method: "POST",
      headers: {
        "Content-Type": "application/json"
      },
      body: JSON.stringify(payload),
      signal: controller.signal
    });
    clearTimeout(timeoutId);
    state.pendingRequestController = null;

    const result = await response.json();
    if (!response.ok) {
      throw new Error(result.error || "Unable to generate a solution right now.");
    }

    state.latestResult = result;
    state.codeBlocks = buildCodeBlocks(result);
    state.selectedCodeIndex = 0;
    state.activeHistoryId = result.historyId || null;
    state.isInputCollapsed = true;
    if (result.account) {
      applyAccountSnapshot(result.account);
    }
    updateInputView();
    renderResult();
    await refreshHistory();
    void window.solverAuth?.refreshSession?.();
  } catch (error) {
    state.pendingRequestController = null;
    if (error.name === "AbortError") {
      showMessage("This request took too long. Try fewer extra languages or a shorter explanation depth.");
    } else {
      showMessage(error.message || "Unable to generate a solution right now.");
    }
  } finally {
    setLoading(false);
  }
}

async function handleAuthChange(event) {
  const previousUser = state.user;
  state.user = event.detail?.user || null;
  state.billingCheckout = null;
  renderPlanState();
  updateWorkspaceAccessState();

  if (!state.user) {
    state.historyItems = [];
    state.activeHistoryId = null;
    closePremiumModal();
    renderHistory();
    return;
  }

  if (!previousUser) {
    const flashMessage = window.solverAuth?.consumeFlash?.();
    if (flashMessage) {
      showMessage(flashMessage);
    } else if (state.user.emailVerified) {
      showMessage("Workspace unlocked. You can generate and save solutions now.");
    } else {
      showMessage("Verification pending. Check your inbox before generating code.");
    }
  }

  await refreshHistory();
}

async function refreshHistory() {
  if (!state.user) {
    renderHistory();
    return;
  }

  try {
    const response = await fetch("/api/history");
    const result = await response.json();
    if (!response.ok) {
      throw new Error(result.error || "Unable to load saved chats.");
    }

    state.historyItems = Array.isArray(result.items) ? result.items : [];
  } catch (error) {
    state.historyItems = [];
    showMessage(error.message || "Unable to load saved chats.");
  }

  renderHistory();
}

function renderPlanState() {
  if (!planTitle || !planCopy || !planBadge || !upgradeButton) {
    return;
  }

  if (!state.user) {
    planTitle.textContent = "Free access";
    planCopy.textContent = "Sign in to generate up to 6 solutions per day, or upgrade for unlimited solves.";
    planBadge.textContent = "Guest";
    upgradeButton.hidden = false;
    upgradeButton.textContent = "Sign in to upgrade";
    return;
  }

  if (!state.user.emailVerified) {
    planTitle.textContent = "Email verification pending";
    planCopy.textContent = "Check your inbox for the confirmation link. Solver output and premium upgrade unlock after you verify your email.";
    planBadge.textContent = "Verify first";
    upgradeButton.hidden = false;
    upgradeButton.textContent = "Resend verification email";
    return;
  }

  if (state.user.premium) {
    const premiumLabel = state.user.premiumPlanLabel || "Premium access";
    const premiumUntil = formatDate(state.user.premiumExpiresAt);
    planTitle.textContent = premiumLabel;
    planCopy.textContent = premiumUntil
      ? `Unlimited daily solves are active until ${premiumUntil}.`
      : "Unlimited daily solves are active for this account.";
    planBadge.textContent = premiumUntil ? `Active until ${premiumUntil}` : "Unlimited";
    upgradeButton.hidden = true;
    return;
  }

  const dailyLimit = Number(state.user.dailyLimit || 6);
  const remaining = Math.max(0, Number(state.user.dailyRemaining || 0));
  planTitle.textContent = "Free access";
  planCopy.textContent = `${remaining} of ${dailyLimit} solves left today. Upgrade to weekly, monthly, or yearly access to remove the daily cap.`;
  planBadge.textContent = remaining > 0 ? `${remaining} left today` : "Daily limit reached";
  upgradeButton.hidden = false;
  upgradeButton.textContent = "View premium plans";
}

function applyAccountSnapshot(account) {
  if (!account) {
    return;
  }

  state.user = account;
  renderPlanState();
  updateWorkspaceAccessState();
}

async function handleUpgradeClick() {
  if (!state.user) {
    showMessage("Sign in first to upgrade this account.");
    window.solverAuth?.open?.("signin");
    return;
  }

  if (!state.user.emailVerified) {
    try {
      await window.solverAuth?.resendVerification?.();
      showMessage("Verification email sent. Check your inbox, then come back to unlock the solver.");
    } catch (error) {
      showMessage(error.message || "Unable to resend the verification email right now.");
    }
    return;
  }

  if (state.user.premium) {
    showMessage("Premium is already active on this account.");
    renderPlanState();
    return;
  }

  await openPremiumModal();
}

async function openPremiumModal() {
  if (!premiumModal) {
    return;
  }

  premiumModal.hidden = false;
  clearPremiumMessage();
  if (premiumReferenceInput) {
    premiumReferenceInput.value = "";
  }

  try {
    const checkout = await loadBillingCheckout();
    renderBillingCheckout(checkout);
  } catch (error) {
    showPremiumMessage(error.message || "Unable to open premium checkout right now.");
  }
}

function closePremiumModal() {
  if (!premiumModal) {
    return;
  }

  premiumModal.hidden = true;
  clearPremiumMessage();
  setPremiumSubmitting(false);
}

async function loadBillingCheckout() {
  if (state.billingCheckout) {
    return state.billingCheckout;
  }

  const response = await fetch("/api/billing/checkout");
  const result = await response.json();
  if (!response.ok) {
    throw new Error(result.error || "Unable to open premium checkout right now.");
  }

  state.billingCheckout = result;
  return result;
}

function renderBillingCheckout(checkout) {
  if (!premiumPlanOptions || !premiumPlanLabel || !premiumPrice || !premiumDuration || !premiumUpiId || !premiumNote || !premiumPayLink) {
    return;
  }

  const plans = Array.isArray(checkout.plans) ? checkout.plans : [];
  if (!plans.length) {
    premiumPlanOptions.innerHTML = "";
    premiumPlanLabel.textContent = "Premium unavailable";
    premiumPrice.textContent = "INR -";
    premiumDuration.textContent = "No active plans are configured right now.";
    premiumUpiId.textContent = checkout.upiId || "-";
    premiumNote.textContent = "Premium checkout is unavailable right now.";
    premiumPayLink.href = "#";
    return;
  }

  if (!state.selectedPremiumPlanCode || !plans.some((plan) => plan.code === state.selectedPremiumPlanCode)) {
    state.selectedPremiumPlanCode = checkout.defaultPlanCode || plans[0].code;
  }

  premiumPlanOptions.innerHTML = plans
    .map((plan) => {
      const selected = plan.code === state.selectedPremiumPlanCode;
      return `
        <button
          class="premium-plan-option${selected ? " is-selected" : ""}"
          type="button"
          data-premium-plan="${escapeHtml(plan.code || "")}"
        >
          <span>${escapeHtml(plan.label || "Plan")}</span>
          <strong>INR ${escapeHtml(plan.priceInr || 0)}</strong>
          <small>${escapeHtml(plan.cycleLabel || "")}</small>
        </button>
      `;
    })
    .join("");

  const selectedPlan = getSelectedPremiumPlan(checkout);
  if (!selectedPlan) {
    return;
  }

  premiumPlanLabel.textContent = selectedPlan.label || "Premium plan";
  premiumPrice.textContent = `INR ${escapeHtml(selectedPlan.priceInr || 0)}`;
  premiumDuration.textContent = selectedPlan.cycleLabel || "Unlimited daily solves while this plan stays active.";
  premiumUpiId.textContent = checkout.upiId || "-";
  premiumNote.textContent = selectedPlan.note
    ? `Pay to ${checkout.upiName || "our UPI ID"} and keep this note or reference: ${selectedPlan.note}`
    : "Use any UPI app to complete the payment.";
  premiumPayLink.href = selectedPlan.upiUrl || "#";
}

function getSelectedPremiumPlan(checkout = state.billingCheckout) {
  const plans = Array.isArray(checkout?.plans) ? checkout.plans : [];
  return plans.find((plan) => plan.code === state.selectedPremiumPlanCode) || plans[0] || null;
}

function handlePremiumPlanSelection(event) {
  const button = event.target.closest("[data-premium-plan]");
  if (!button || !state.billingCheckout) {
    return;
  }

  state.selectedPremiumPlanCode = button.dataset.premiumPlan || "";
  renderBillingCheckout(state.billingCheckout);
}

async function copyPremiumUpiId() {
  const value = premiumUpiId?.textContent?.trim();
  if (!value || value === "-") {
    showPremiumMessage("UPI ID is not available right now.");
    return;
  }

  try {
    await navigator.clipboard.writeText(value);
    showPremiumMessage("UPI ID copied. Complete the payment, then paste your UTR here.");
  } catch (error) {
    showPremiumMessage("Clipboard access failed. Copy the UPI ID manually.");
  }
}

async function handlePremiumSubmit(event) {
  event.preventDefault();
  if (!state.user) {
    showPremiumMessage("Sign in first to upgrade this account.");
    return;
  }

  const transactionReference = premiumReferenceInput?.value?.trim() || "";
  if (!transactionReference) {
    showPremiumMessage("Enter the payment reference or UTR after you pay.");
    return;
  }

  const selectedPlan = getSelectedPremiumPlan();
  if (!selectedPlan) {
    showPremiumMessage("Choose a premium plan first.");
    return;
  }

  setPremiumSubmitting(true);
  clearPremiumMessage();

  try {
    const response = await fetch("/api/billing/upgrade", {
      method: "POST",
      headers: {
        "Content-Type": "application/json"
      },
      body: JSON.stringify({
        planCode: selectedPlan.code,
        transactionReference
      })
    });
    const result = await response.json();
    if (!response.ok) {
      throw new Error(result.error || "Unable to activate premium right now.");
    }

    applyAccountSnapshot(result.user || null);
    state.billingCheckout = null;
    closePremiumModal();
    if (result.alreadyPremium) {
      showMessage("Premium is already active on this account.");
      return;
    }
    if (result.premiumEmailSent === false) {
      showMessage(result.premiumEmailNotice || "Premium unlocked. Confirmation email could not be sent right now.");
    } else {
      const unlockedUntil = formatDate(result.user?.premiumExpiresAt);
      showMessage(
        unlockedUntil
          ? `Premium unlocked until ${unlockedUntil}. A confirmation email is on the way.`
          : "Premium unlocked. A confirmation email is on the way."
      );
    }
    void window.solverAuth?.refreshSession?.();
  } catch (error) {
    showPremiumMessage(error.message || "Unable to activate premium right now.");
  } finally {
    setPremiumSubmitting(false);
  }
}

function setPremiumSubmitting(isSubmitting) {
  state.isPremiumSubmitting = isSubmitting;
  if (premiumSubmitButton) {
    premiumSubmitButton.disabled = isSubmitting;
  }
  if (premiumCopyUpi) {
    premiumCopyUpi.disabled = isSubmitting;
  }
  if (premiumPayLink) {
    premiumPayLink.style.pointerEvents = isSubmitting ? "none" : "";
    premiumPayLink.style.opacity = isSubmitting ? "0.72" : "";
  }
}

function showPremiumMessage(message) {
  if (!premiumMessage) {
    return;
  }
  premiumMessage.hidden = false;
  premiumMessage.textContent = message;
}

function clearPremiumMessage() {
  if (!premiumMessage) {
    return;
  }
  premiumMessage.hidden = true;
  premiumMessage.textContent = "";
}

if (premiumPlanOptions) {
  premiumPlanOptions.addEventListener("click", handlePremiumPlanSelection);
}

function renderHistory() {
  if (!state.user) {
    historyGuest.hidden = false;
    historyEmpty.hidden = true;
    historyList.hidden = true;
    historyList.innerHTML = "";
    historySearch.disabled = true;
    historySearch.value = "";
    updateHistorySummary("Sign in to unlock history");
    return;
  }

  historyGuest.hidden = true;
  historySearch.disabled = false;

  const filteredItems = filterHistoryItems();
  if (!state.historyItems.length) {
    historyEmpty.hidden = false;
    historyEmpty.textContent = "No saved chats yet.";
    historyList.hidden = true;
    historyList.innerHTML = "";
    updateHistorySummary("0 saved chats");
    return;
  }

  if (!filteredItems.length) {
    historyEmpty.hidden = false;
    historyEmpty.textContent = "No chats match this search.";
    historyList.hidden = true;
    historyList.innerHTML = "";
    updateHistorySummary("No search matches");
    return;
  }

  historyEmpty.hidden = true;
  historyList.hidden = false;
  historyList.innerHTML = filteredItems
    .map(
      (item) => `
        <article class="history-card ${item.id === state.activeHistoryId ? "is-active" : ""}">
          <button class="history-open" type="button" data-history-action="open" data-history-id="${escapeHtml(item.id || "")}">
            <div class="history-card-top">
              <span class="history-language">${escapeHtml(item.language || "Code")}</span>
              ${item.pinned ? '<span class="history-badge">Pinned</span>' : ""}
            </div>
            <strong>${escapeHtml(item.title || "Saved coding chat")}</strong>
            <p>${escapeHtml(item.preview || "")}</p>
            <span class="history-meta">${escapeHtml(formatTimestamp(item.createdAt))}</span>
          </button>
          <div class="history-card-actions">
            <button class="history-action-button" type="button" data-history-action="pin" data-history-id="${escapeHtml(item.id || "")}" data-pinned="${item.pinned ? "true" : "false"}">
              ${item.pinned ? "Unpin" : "Pin"}
            </button>
            <button class="history-action-button" type="button" data-history-action="retry" data-history-id="${escapeHtml(item.id || "")}">
              Retry
            </button>
            <button class="history-action-button history-action-danger" type="button" data-history-action="delete" data-history-id="${escapeHtml(item.id || "")}">
              Delete
            </button>
          </div>
        </article>
      `
    )
    .join("");

  const pinnedCount = filteredItems.filter((item) => item.pinned).length;
  updateHistorySummary(`${filteredItems.length} chats · ${pinnedCount} pinned`);
}

function filterHistoryItems() {
  const query = state.historyQuery.trim().toLowerCase();
  if (!query) {
    return state.historyItems;
  }

  return state.historyItems.filter((item) => {
    const haystack = `${item.title || ""} ${item.preview || ""} ${item.language || ""}`.toLowerCase();
    return haystack.includes(query);
  });
}

function handleHistorySearch(event) {
  state.historyQuery = event.target.value || "";
  renderHistory();
}

function updateHistorySummary(text) {
  historySummary.textContent = text;
}

async function handleHistoryActions(event) {
  const actionButton = event.target.closest("[data-history-action]");
  if (!actionButton) {
    return;
  }

  const action = actionButton.dataset.historyAction;
  const id = actionButton.dataset.historyId;
  if (!id) {
    return;
  }

  if (action === "open") {
    await loadHistoryItem(id);
    return;
  }

  if (action === "retry") {
    await retryHistoryItem(id);
    return;
  }

  if (action === "pin") {
    const pinned = actionButton.dataset.pinned === "true";
    await toggleHistoryPin(id, !pinned);
    return;
  }

  if (action === "delete") {
    const confirmed = window.confirm("Delete this saved chat?");
    if (confirmed) {
      await deleteHistoryItem(id);
    }
  }
}

async function loadHistoryItem(id) {
  hideMessage();
  setLoading(true);

  try {
    const item = await fetchHistoryItem(id);
    setFormData(item.request || {});
    persistDraft();
    state.latestResult = item.result || null;
    state.codeBlocks = buildCodeBlocks(state.latestResult || {});
    state.selectedCodeIndex = 0;
    state.activeHistoryId = id;
    state.isInputCollapsed = true;
    updateInputView();
    renderResult();
    renderHistory();
  } catch (error) {
    showMessage(error.message || "Unable to open the saved chat.");
  } finally {
    setLoading(false);
  }
}

async function retryHistoryItem(id) {
  try {
    const item = await fetchHistoryItem(id);
    setFormData(item.request || {});
    persistDraft();
    await runGeneration(item.request || getFormData());
  } catch (error) {
    showMessage(error.message || "Unable to retry this chat.");
  }
}

async function toggleHistoryPin(id, pinned) {
  try {
    const response = await fetch("/api/history/pin", {
      method: "POST",
      headers: {
        "Content-Type": "application/json"
      },
      body: JSON.stringify({ id, pinned })
    });
    const result = await response.json();
    if (!response.ok) {
      throw new Error(result.error || "Unable to update this chat.");
    }

    state.historyItems = state.historyItems.map((item) =>
      item.id === id ? { ...item, ...(result.item || {}), pinned } : item
    );
    renderHistory();
  } catch (error) {
    showMessage(error.message || "Unable to update this chat.");
  }
}

async function deleteHistoryItem(id) {
  try {
    const response = await fetch("/api/history/delete", {
      method: "POST",
      headers: {
        "Content-Type": "application/json"
      },
      body: JSON.stringify({ id })
    });
    const result = await response.json();
    if (!response.ok) {
      throw new Error(result.error || "Unable to delete this chat.");
    }

    state.historyItems = state.historyItems.filter((item) => item.id !== id);
    if (state.activeHistoryId === id) {
      state.activeHistoryId = null;
    }
    renderHistory();
  } catch (error) {
    showMessage(error.message || "Unable to delete this chat.");
  }
}

async function fetchHistoryItem(id) {
  const response = await fetch(`/api/history/item?id=${encodeURIComponent(id)}`);
  const result = await response.json();
  if (!response.ok) {
    throw new Error(result.error || "Unable to open the saved chat.");
  }
  return result;
}

function renderResult() {
  if (!state.latestResult) {
    return;
  }

  const result = state.latestResult;
  const activeCode = state.codeBlocks[state.selectedCodeIndex] || null;

  emptyState.hidden = true;
  resultView.hidden = false;

  resultView.innerHTML = `
    <article class="result-panel result-panel-code">
      <header class="result-head">
        <div>
          <p class="section-kicker section-kicker-light">Code first</p>
          <h3>${escapeHtml(result.title || "Generated solution")}</h3>
          ${
            result.restatedProblem
              ? `<p class="result-summary">${escapeHtml(result.restatedProblem)}</p>`
              : ""
          }
          <div class="pill-row">
            <span class="pill pill-primary">${escapeHtml(result.primaryLanguage || "Primary")}</span>
            ${(result.assumptions || [])
              .map((assumption) => `<span class="pill pill-coral">${escapeHtml(assumption)}</span>`)
              .join("")}
          </div>
          <div class="result-metrics">
            <div class="metric-card metric-card-time">
              <span>Time</span>
              <strong>${escapeHtml(result.complexity?.time || "Not provided")}</strong>
            </div>
            <div class="metric-card metric-card-space">
              <span>Space</span>
              <strong>${escapeHtml(result.complexity?.space || "Not provided")}</strong>
            </div>
          </div>
        </div>
      </header>

      ${
        activeCode
          ? `
            <div class="code-shell">
              <div class="code-toolbar">
                <div class="tab-row">
                  ${state.codeBlocks
                    .map(
                      (block, index) => `
                        <button
                          class="tab-button ${index === state.selectedCodeIndex ? "is-active" : ""}"
                          type="button"
                          data-tab-index="${index}"
                        >
                          ${escapeHtml(block.language)}
                        </button>
                      `
                    )
                    .join("")}
                </div>
                <button class="copy-button" type="button" data-copy-index="${state.selectedCodeIndex}">
                  Copy code
                </button>
              </div>
              <pre><code>${escapeHtml(activeCode.code || "")}</code></pre>
            </div>
          `
          : `<div class="result-section"><p>No code block returned for this request.</p></div>`
      }
    </article>

    <article class="result-panel">
      <div class="section-head section-head-light">
        <div>
          <p class="section-kicker section-kicker-light">Study notes</p>
          <h2>How the solution works</h2>
        </div>
      </div>
      <div class="analysis-grid analysis-grid-study">
        ${renderSection("Core idea", `<p>${escapeHtml(result.coreIdea || "No summary returned.")}</p>`, "span-2")}
        ${renderSection("Algorithm steps", renderBulletList(result.algorithmSteps), "span-2")}
        ${renderSection("Constraint fit", renderBulletList(result.constraintFit))}
        ${renderSection("Edge cases", renderBulletList(result.edgeCases))}
        ${renderSection("Pseudocode", renderBulletList(result.pseudocode), "span-2")}
      </div>
    </article>

    <article class="result-panel">
      <div class="analysis-grid">
        ${renderSection("Focused tests", renderTestsTable(result.tests), "span-2")}
        ${renderSection("Notes", renderBulletList(result.notes))}
      </div>
    </article>
  `;
}

function renderSection(title, body, extraClass = "") {
  return `
    <section class="result-section ${extraClass}">
      <h4>${escapeHtml(title)}</h4>
      ${body}
    </section>
  `;
}

function renderBulletList(items) {
  if (!Array.isArray(items) || !items.length) {
    return "<p>Not provided.</p>";
  }

  return `<ul class="bullet-list">${items.map((item) => `<li>${escapeHtml(item)}</li>`).join("")}</ul>`;
}

function renderTestsTable(tests) {
  if (!Array.isArray(tests) || !tests.length) {
    return "<p>No tests returned.</p>";
  }

  return `
    <table class="tests-table">
      <thead>
        <tr>
          <th>Input</th>
          <th>Expected output</th>
          <th>Reason</th>
        </tr>
      </thead>
      <tbody>
        ${tests
          .map(
            (test) => `
              <tr>
                <td><code>${escapeHtml(test.input || "")}</code></td>
                <td><code>${escapeHtml(test.expectedOutput || "")}</code></td>
                <td>${escapeHtml(test.reason || "")}</td>
              </tr>
            `
          )
          .join("")}
      </tbody>
    </table>
  `;
}

function buildCodeBlocks(result) {
  const blocks = [];

  if (result.code) {
    blocks.push({
      language: result.primaryLanguage || "Primary",
      code: result.code
    });
  }

  if (Array.isArray(result.alternateImplementations)) {
    for (const block of result.alternateImplementations) {
      if (block.language && block.code) {
        blocks.push(block);
      }
    }
  }

  return blocks;
}

async function handleResultActions(event) {
  const tabButton = event.target.closest("[data-tab-index]");
  if (tabButton) {
    state.selectedCodeIndex = Number(tabButton.dataset.tabIndex);
    renderResult();
    return;
  }

  const copyButton = event.target.closest("[data-copy-index]");
  if (!copyButton) {
    return;
  }

  const block = state.codeBlocks[Number(copyButton.dataset.copyIndex)];
  if (!block?.code) {
    return;
  }

  try {
    await navigator.clipboard.writeText(block.code);
    const previousText = copyButton.textContent;
    copyButton.textContent = "Copied";
    setTimeout(() => {
      copyButton.textContent = previousText;
    }, 1200);
  } catch (error) {
    showMessage("Clipboard access failed. Copy from the code panel.");
  }
}

function appendRequirement(text) {
  const input = document.querySelector("#additional-requirements");
  const nextValue = input.value.trim();
  input.value = nextValue ? `${nextValue} ${text}` : text;
  persistDraft();
  if (state.latestResult && state.isInputCollapsed) {
    renderInputSummary();
  }
}

function setLoading(isLoading) {
  state.isLoading = isLoading;
  loadingState.hidden = !isLoading;

  if (isLoading) {
    emptyState.hidden = true;
    resultView.hidden = true;
    resultView.innerHTML = "";
    startLoadingSequence();
  } else {
    stopLoadingSequence();
  }

  generateButton.disabled = isLoading;
  updateWorkspaceAccessState();
}

function startLoadingSequence() {
  state.loadingStageIndex = 0;
  renderLoadingStage();
  stopLoadingTimer();
  state.loadingTimer = window.setInterval(() => {
    state.loadingStageIndex = (state.loadingStageIndex + 1) % LOADING_STAGES.length;
    renderLoadingStage();
  }, 1500);
}

function renderLoadingStage() {
  const stage = LOADING_STAGES[state.loadingStageIndex];
  loadingTitle.textContent = stage.title;
  loadingCopy.textContent = stage.copy;
  loadingSteps.innerHTML = LOADING_STAGES.map(
    (item, index) => `
      <span class="loading-step-pill ${index <= state.loadingStageIndex ? "is-active" : ""}">
        ${escapeHtml(item.title)}
      </span>
    `
  ).join("");
}

function stopLoadingSequence() {
  stopLoadingTimer();
  loadingTitle.textContent = "Analyzing the problem";
  loadingCopy.textContent = "Checking the constraints, selecting an algorithm, and building the answer.";
  loadingSteps.innerHTML = "";
}

function stopLoadingTimer() {
  if (state.loadingTimer) {
    window.clearInterval(state.loadingTimer);
    state.loadingTimer = null;
  }
}

function loadDemo() {
  setFormData(DEMO_DATA);
  persistDraft();
  if (state.latestResult && state.isInputCollapsed) {
    renderInputSummary();
  }
  hideMessage();
}

function clearForm() {
  if (state.pendingRequestController) {
    state.pendingRequestController.abort();
    state.pendingRequestController = null;
  }

  form.reset();
  localStorage.removeItem(STORAGE_KEY);
  state.latestResult = null;
  state.codeBlocks = [];
  state.selectedCodeIndex = 0;
  state.activeHistoryId = null;
  state.isInputCollapsed = false;
  resultView.innerHTML = "";
  resultView.hidden = true;
  loadingState.hidden = true;
  emptyState.hidden = false;
  updateInputView();
  renderHistory();
  hideMessage();
}

function persistDraft() {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(getFormData()));
}

function hydrateDraft() {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (!stored) {
      return;
    }
    setFormData(JSON.parse(stored));
  } catch (error) {
    localStorage.removeItem(STORAGE_KEY);
  }
}

function getFormData() {
  const data = new FormData(form);
  return {
    problemStatement: String(data.get("problemStatement") || ""),
    constraints: String(data.get("constraints") || ""),
    examples: String(data.get("examples") || ""),
    primaryLanguage: String(data.get("primaryLanguage") || ""),
    alternateLanguages: String(data.get("alternateLanguages") || ""),
    solutionMode: String(data.get("solutionMode") || ""),
    interfaceStyle: String(data.get("interfaceStyle") || ""),
    explanationDepth: String(data.get("explanationDepth") || ""),
    additionalRequirements: String(data.get("additionalRequirements") || "")
  };
}

function setFormData(data) {
  document.querySelector("#problem-statement").value = data.problemStatement || "";
  document.querySelector("#constraints").value = data.constraints || "";
  document.querySelector("#examples").value = data.examples || "";
  document.querySelector("#primary-language").value = data.primaryLanguage || "Java";
  document.querySelector("#alternate-languages").value = data.alternateLanguages || "";
  document.querySelector("#solution-mode").value = data.solutionMode || "logic_and_code";
  document.querySelector("#interface-style").value = data.interfaceStyle || "competitive_programming";
  document.querySelector("#explanation-depth").value = data.explanationDepth || "balanced";
  document.querySelector("#additional-requirements").value = data.additionalRequirements || "";
}

function toggleInputPanel() {
  if (!state.latestResult) {
    return;
  }

  state.isInputCollapsed = !state.isInputCollapsed;
  updateInputView();
}

function updateInputView() {
  const hasResult = Boolean(state.latestResult);
  const shouldCollapse = hasResult && state.isInputCollapsed;

  document.body.classList.toggle("has-result", hasResult);
  document.body.classList.toggle("input-collapsed", shouldCollapse);

  toggleInputButton.hidden = !hasResult;
  toggleInputButton.textContent = shouldCollapse ? "Edit prompt" : "Hide prompt";
  form.hidden = shouldCollapse;
  inputSummary.hidden = !shouldCollapse;

  if (shouldCollapse) {
    renderInputSummary();
  } else {
    inputSummary.innerHTML = "";
  }
}

function renderInputSummary() {
  const data = getFormData();
  inputSummary.innerHTML = `
    <p class="input-summary-text">${escapeHtml(truncateText(data.problemStatement, 240))}</p>
    <div class="input-summary-grid">
      ${renderSummaryCard("Main", data.primaryLanguage || "Not set")}
      ${renderSummaryCard("Extra", data.alternateLanguages || "None")}
      ${renderSummaryCard("Mode", readableLabel(data.solutionMode))}
      ${renderSummaryCard("Style", readableLabel(data.interfaceStyle))}
    </div>
  `;
}

function renderSummaryCard(label, value) {
  return `
    <article class="summary-card">
      <span>${escapeHtml(label)}</span>
      <strong>${escapeHtml(value)}</strong>
    </article>
  `;
}

function readableLabel(value) {
  return String(value || "")
    .replaceAll("_", " ")
    .replace(/\b\w/g, (match) => match.toUpperCase()) || "Not set";
}

function truncateText(value, maxLength) {
  const text = String(value || "").trim();
  if (text.length <= maxLength) {
    return text;
  }
  return `${text.slice(0, maxLength).trimEnd()}...`;
}

function formatTimestamp(value) {
  if (!value) {
    return "Saved chat";
  }

  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return "Saved chat";
  }

  return date.toLocaleString([], {
    dateStyle: "medium",
    timeStyle: "short"
  });
}

function formatDate(value) {
  if (!value) {
    return "";
  }

  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return "";
  }

  return date.toLocaleDateString([], {
    dateStyle: "medium"
  });
}

function showMessage(message) {
  messageBanner.hidden = false;
  messageBanner.textContent = message;
}

function hideMessage() {
  messageBanner.hidden = true;
  messageBanner.textContent = "";
}

function updateWorkspaceAccessState() {
  if (!generateButton || !submitHint || !emptyStateTitle || !emptyStateCopy) {
    return;
  }

  if (state.isLoading) {
    generateButton.textContent = "Forging...";
    return;
  }

  if (state.user) {
    if (!state.user.emailVerified) {
      generateButton.textContent = "Verify email first";
      submitHint.textContent = "Check your inbox for the confirmation link. Use the plan button above to resend it if needed.";
      emptyStateTitle.textContent = "Email verification pending";
      emptyStateCopy.textContent =
        "Generation and premium upgrade unlock after you confirm your email.";
      return;
    }

    if (state.user.premium) {
      const premiumLabel = state.user.premiumPlanLabel || "Premium";
      const premiumUntil = formatDate(state.user.premiumExpiresAt);
      generateButton.textContent = "Forge solution";
      submitHint.textContent = premiumUntil
        ? `${premiumLabel} active until ${premiumUntil}. Unlimited daily solves are available on this account.`
        : "Premium active. Unlimited daily solves are available on this account.";
      emptyStateTitle.textContent = `${premiumLabel} ready`;
      emptyStateCopy.textContent =
        "Paste any problem and generate as many structured solutions as you need.";
      return;
    }

    const dailyLimit = Number(state.user.dailyLimit || 6);
    const remaining = Math.max(0, Number(state.user.dailyRemaining || 0));
    if (remaining <= 0) {
      generateButton.textContent = "Upgrade for unlimited";
      submitHint.textContent =
        `Free plan limit reached for today. You used ${dailyLimit} of ${dailyLimit} solves. Upgrade to premium for unlimited access.`;
      emptyStateTitle.textContent = "Daily free limit reached";
      emptyStateCopy.textContent =
        "Upgrade with UPI to keep generating solutions without a daily cap.";
      return;
    }

    generateButton.textContent = "Forge solution";
    submitHint.textContent = `Free plan: ${remaining} of ${dailyLimit} solves left today. Focus the request on the exact task and constraints.`;
    emptyStateTitle.textContent = "Ready for the next problem";
    emptyStateCopy.textContent =
      "The result view will show the core idea, algorithm steps, complexity, code tabs, and focused tests.";
    return;
  }

  generateButton.textContent = "Sign in to unlock";
  submitHint.textContent = "Sign in first. Code output and saved history are available only for authenticated users.";
  emptyStateTitle.textContent = "Sign in to unlock code output";
  emptyStateCopy.textContent =
    "Open sign in first, then generate full solutions, save chats, and reopen them from your history.";
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}
