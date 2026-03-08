const REVIEW_KEY_STORAGE = "spectrum-review-key";

const reviewKeyForm = document.querySelector("#review-key-form");
const reviewKeyInput = document.querySelector("#review-key");
const reviewKeyToggle = document.querySelector("#review-key-toggle");
const reviewRefreshButton = document.querySelector("#review-refresh");
const reviewMessage = document.querySelector("#review-message");
const reviewCount = document.querySelector("#review-count");
const reviewEmpty = document.querySelector("#review-empty");
const reviewList = document.querySelector("#review-list");
const reviewSearch = document.querySelector("#review-search");

let pendingPayments = [];
let isReviewLoading = false;
let reviewQuery = "";

bootstrapReview();

function bootstrapReview() {
  if (reviewKeyInput) {
    reviewKeyInput.value = localStorage.getItem(REVIEW_KEY_STORAGE) || "";
  }

  if (reviewKeyForm) {
    reviewKeyForm.addEventListener("submit", handleReviewLoad);
  }

  if (reviewRefreshButton) {
    reviewRefreshButton.addEventListener("click", () => void loadPendingPayments());
  }

  if (reviewKeyToggle) {
    reviewKeyToggle.addEventListener("click", toggleReviewKeyVisibility);
  }

  if (reviewList) {
    reviewList.addEventListener("click", handleReviewActions);
  }

  if (reviewSearch) {
    reviewSearch.addEventListener("input", handleReviewSearch);
  }

  renderReviewList();
}

async function handleReviewLoad(event) {
  event.preventDefault();
  await loadPendingPayments();
}

async function loadPendingPayments() {
  const reviewKey = (reviewKeyInput?.value || "").trim();
  if (!reviewKey) {
    showReviewMessage("Enter the premium review key first.");
    return;
  }

  localStorage.setItem(REVIEW_KEY_STORAGE, reviewKey);
  setReviewLoading(true);
  clearReviewMessage();

  try {
    const response = await fetch("/api/billing/review/pending", {
      headers: {
        "X-Review-Key": reviewKey
      }
    });
    const result = await response.json();
    if (!response.ok) {
      throw new Error(result.error || "Unable to load pending payments.");
    }

    pendingPayments = Array.isArray(result.payments) ? result.payments : [];
    renderReviewList();
  } catch (error) {
    showReviewMessage(error.message || "Unable to load pending payments.");
  } finally {
    setReviewLoading(false);
  }
}

async function handleReviewActions(event) {
  const approveButton = event.target.closest("[data-review-approve]");
  const rejectButton = event.target.closest("[data-review-reject]");
  if (!approveButton && !rejectButton) {
    return;
  }

  const paymentId = approveButton?.dataset.reviewApprove || rejectButton?.dataset.reviewReject || "";
  if (!paymentId) {
    return;
  }

  const reviewKey = (reviewKeyInput?.value || "").trim();
  if (!reviewKey) {
    showReviewMessage("Enter the premium review key first.");
    return;
  }

  setReviewLoading(true);
  clearReviewMessage();

  try {
    const endpoint = approveButton ? "/api/billing/review/approve" : "/api/billing/review/reject";
    const body = approveButton
      ? { paymentId }
      : { paymentId, note: "Rejected during manual UTR review." };

    const response = await fetch(endpoint, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Review-Key": reviewKey
      },
      body: JSON.stringify(body)
    });
    const result = await response.json();
    if (!response.ok) {
      throw new Error(result.error || "Unable to update the payment review.");
    }

    pendingPayments = pendingPayments.filter((payment) => payment.id !== paymentId);
    renderReviewList();

    if (approveButton) {
      showReviewMessage("Payment approved. Premium is active on that account now.");
    } else {
      showReviewMessage("Payment rejected. The user stays on the free plan.");
    }
  } catch (error) {
    showReviewMessage(error.message || "Unable to update the payment review.");
  } finally {
    setReviewLoading(false);
  }
}

function renderReviewList() {
  const filteredPayments = filterPendingPayments();

  if (reviewCount) {
    reviewCount.textContent = String(filteredPayments.length);
  }

  if (!reviewList || !reviewEmpty) {
    return;
  }

  if (!pendingPayments.length) {
    reviewList.hidden = true;
    reviewList.innerHTML = "";
    reviewEmpty.hidden = false;
    reviewEmpty.textContent = isReviewLoading
      ? "Loading pending payments..."
      : "No pending UTR submissions right now.";
    return;
  }

  if (!filteredPayments.length) {
    reviewList.hidden = true;
    reviewList.innerHTML = "";
    reviewEmpty.hidden = false;
    reviewEmpty.textContent = "No pending payments match this search.";
    return;
  }

  reviewEmpty.hidden = true;
  reviewList.hidden = false;
  reviewList.innerHTML = filteredPayments
    .map(
      (payment) => `
        <article class="builder-panel review-item">
          <div class="review-item-head">
            <div>
              <p class="section-kicker section-kicker-light">Pending payment</p>
              <h3>${escapeHtml(payment.planLabel || "Premium plan")}</h3>
            </div>
            <div class="plan-badge">INR ${escapeHtml(payment.amountInr || 0)}</div>
          </div>
          <div class="review-utr-card">
            <span>UTR</span>
            <strong>${escapeHtml(payment.reference || "-")}</strong>
          </div>
          <div class="review-meta-grid">
            <div><span>User</span><strong>${escapeHtml(payment.userName || "-")}</strong></div>
            <div><span>Email</span><strong>${escapeHtml(payment.userEmail || "-")}</strong></div>
            <div><span>UPI ID</span><strong>${escapeHtml(payment.upiId || "-")}</strong></div>
            <div><span>Submitted</span><strong>${escapeHtml(formatDate(payment.createdAt) || "-")}</strong></div>
          </div>
          <div class="review-actions">
            <button class="primary-button" type="button" data-review-approve="${escapeHtml(payment.id || "")}">Approve</button>
            <button class="ghost-button ghost-button-light" type="button" data-review-reject="${escapeHtml(payment.id || "")}">Reject</button>
          </div>
        </article>
      `
    )
    .join("");
}

function handleReviewSearch(event) {
  reviewQuery = String(event.target?.value || "").trim().toLowerCase();
  renderReviewList();
}

function filterPendingPayments() {
  if (!reviewQuery) {
    return pendingPayments;
  }

  return pendingPayments.filter((payment) => {
    const haystack = [
      payment.reference,
      payment.userEmail,
      payment.userName,
      payment.planLabel,
      payment.upiId
    ]
      .filter(Boolean)
      .join(" ")
      .toLowerCase();
    return haystack.includes(reviewQuery);
  });
}

function setReviewLoading(isLoading) {
  isReviewLoading = isLoading;
  if (reviewRefreshButton) {
    reviewRefreshButton.disabled = isLoading;
  }
  if (reviewKeyForm) {
    const buttons = reviewKeyForm.querySelectorAll("button");
    for (const button of buttons) {
      button.disabled = isLoading;
    }
  }
}

function toggleReviewKeyVisibility() {
  if (!reviewKeyInput || !reviewKeyToggle) {
    return;
  }
  const showing = reviewKeyInput.type === "text";
  reviewKeyInput.type = showing ? "password" : "text";
  reviewKeyToggle.textContent = showing ? "Show" : "Hide";
}

function showReviewMessage(message) {
  if (!reviewMessage) {
    return;
  }
  reviewMessage.hidden = false;
  reviewMessage.textContent = message;
}

function clearReviewMessage() {
  if (!reviewMessage) {
    return;
  }
  reviewMessage.hidden = true;
  reviewMessage.textContent = "";
}

function formatDate(value) {
  if (!value) {
    return "";
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return "";
  }
  return date.toLocaleString(undefined, {
    day: "numeric",
    month: "short",
    hour: "numeric",
    minute: "2-digit"
  });
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}
