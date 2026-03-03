import * as amplitude from "@amplitude/analytics-browser";

amplitude.init(
  "6f4d7037eb318be07f4d9641d4fadce",
  {
    defaultTracking: false, // we control events manually
  }
);

export default amplitude;
