import "@mantine/core/styles.css";
import "@mantine/notifications/styles.css";

import { Center, Loader, MantineProvider, Text, createTheme } from "@mantine/core";
import { Notifications } from "@mantine/notifications";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import React from "react";
import ReactDOM from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import App from "./App";
import { initAuth } from "./auth";

const theme = createTheme({
  primaryColor: "blue",
  defaultRadius: "md",
  // Las insignias no se truncan dentro de tablas angostas
  components: { Badge: { styles: { root: { flexShrink: 0, overflow: "visible" }, label: { overflow: "visible" } } } },
});
const queryClient = new QueryClient({ defaultOptions: { queries: { refetchOnWindowFocus: false, retry: 1 } } });
const root = ReactDOM.createRoot(document.getElementById("root")!);

function Shell({ children }: { children: React.ReactNode }) {
  return (
    <MantineProvider theme={theme} defaultColorScheme="auto">
      {children}
    </MantineProvider>
  );
}

root.render(
  <Shell>
    <Center h="100vh">
      <Loader />
    </Center>
  </Shell>,
);

initAuth()
  .then(() =>
    root.render(
      <React.StrictMode>
        <Shell>
          <Notifications position="top-right" />
          <QueryClientProvider client={queryClient}>
            <BrowserRouter>
              <App />
            </BrowserRouter>
          </QueryClientProvider>
        </Shell>
      </React.StrictMode>,
    ),
  )
  .catch((e) =>
    root.render(
      <Shell>
        <Center h="100vh">
          <Text c="red">No se pudo iniciar sesión: {String(e)}</Text>
        </Center>
      </Shell>,
    ),
  );
