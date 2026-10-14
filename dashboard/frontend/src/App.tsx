import { AppProvider, ToastProvider } from "./components/AppContext";
import { Layout } from "./components/Layout";
import { useRoute } from "./lib/hooks";
import { ContributePage } from "./pages/ContributePage";
import { ContributionsPage } from "./pages/ContributionsPage";
import { DataPage } from "./pages/DataPage";
import { EnginesPage } from "./pages/EnginesPage";
import { EvaluationPage } from "./pages/EvaluationPage";
import { HomePage } from "./pages/HomePage";
import { TranslatePage } from "./pages/TranslatePage";

const PAGES = {
  home: HomePage,
  translate: TranslatePage,
  contribute: ContributePage,
  review: ContributionsPage,
  data: DataPage,
  engines: EnginesPage,
  evaluation: EvaluationPage,
};

export function App() {
  const route = useRoute();
  const Page = PAGES[route];
  return (
    <AppProvider>
      <ToastProvider>
        <Layout route={route}>
          <Page key={route} />
        </Layout>
      </ToastProvider>
    </AppProvider>
  );
}
