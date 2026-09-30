Teste de regressão do fechamento por RejectedExecutionException.

O GitHub Actions executa este teste antes de gerar o APK. O teste usa executores reais do Java: reproduz a rejeição original, confirma carregamento normal, cancela uma tarefa atrasada e uma tarefa em fila após fechar a tela, e exercita envio simultâneo ao encerramento.

Para executar localmente, use um JDK 17:

```sh
mkdir -p /tmp/lpsm-lifecycle-tests
javac -d /tmp/lpsm-lifecycle-tests android/app/src/main/java/com/lpsm/vod/LifecycleExecutor.java tests/lifecycle/LifecycleExecutorRegression.java
java -cp /tmp/lpsm-lifecycle-tests LifecycleExecutorRegression
```

Isso verifica o controle de tarefas. Testes em celular e TV Box ainda são necessários para outros tipos de falha, codecs e comportamento do fabricante.
