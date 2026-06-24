const BASE_URL = 'http://localhost:5000';

export async function submitOnboardingLead(payload) {
  const response = await fetch(`${BASE_URL}/api/onboarding/leads`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data.error || 'Failed to submit request');
  return data;
}
