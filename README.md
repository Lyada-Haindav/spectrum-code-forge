# Code Forge

Code Forge is a Java full-stack app for coding questions. It accepts a problem statement, reasons from the constraints, and generates structured logic plus runnable code. The app now uses Gemini and separates the public landing page from the builder workspace.

## Features

- Java backend with no external runtime dependencies
- Colorful landing page plus separate solver page
- Constraint-aware generation flow for contest and interview problems
- Primary language output plus optional translated implementations
- Logic summary, algorithm steps, complexity fit, edge cases, tests, and code tabs
- Gemini-based generation flow
- Email verification before solver access
- Free tier with a daily solve limit and premium unlimited access
- UPI-based premium upgrade flow with transaction reference capture
- Premium activation confirmation email

## Configuration

Copy [.env.example](/Users/haindavlyada/Downloads/coder/.env.example) to `.env` and set:

- `GEMINI_API_KEY`
- `GEMINI_BASE_URL` (optional)
- `GEMINI_MODEL` (optional)
- `PORT` (optional, defaults to `3000`)
- `FREE_DAILY_LIMIT` (optional, defaults to `6`)
- `PREMIUM_PRICE_INR` (optional, defaults to `299`)
- `PREMIUM_UPI_ID` (required for the premium UPI checkout flow)
- `PREMIUM_UPI_NAME` (optional, defaults to `Spectrum Code Forge`)
- `BREVO_API_KEY` (required for verification and premium emails)
- `BREVO_SENDER_EMAIL` (required for Brevo delivery)
- `BREVO_SENDER_NAME` (optional, defaults to `Spectrum Code Forge`)
- `APP_BASE_URL` (optional, defaults to `http://localhost:3000`)

## Run

Compile:

```bash
mvn compile
```

Start:

```bash
java -cp target/classes com.spectrumforge.SpectrumCodeForgeApplication
```

Then open [http://localhost:3000](http://localhost:3000) for the landing page or [http://localhost:3000/builder.html](http://localhost:3000/builder.html) for the solver directly.

## Notes

- Gemini is called through its `generateContent` API and the response is normalized into the app's result format.
- Additional languages are capped at three per request to keep the output practical.
- The app remains fully Java-served.
- New accounts must confirm their email before generation or premium upgrade is available.
- Free users are limited per day at the backend, while premium accounts are unlimited.
- The UPI upgrade path stores transaction references locally; for production, replace this with real payment verification.
